#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: verify_huffman.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""原创规范码定向树构造与真实Chisel符号解码，含错误树及负控。"""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
from verify_sample import ROOT,require_frozen,run

def canonical(lengths):
    # 离线按(码长,符号)排序递增规范码，方法不同于硬件逐长度范围构造。
    result={};code=0;previous=0
    for length,symbol in sorted((n,i) for i,n in enumerate(lengths) if n):
        code<<=length-previous
        assert code<1<<length
        result[symbol]=[int(c) for c in f'{code:0{length}b}'];code+=1;previous=length
    return result

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',required=True);ap.add_argument('--negative',action='store_true');ap.add_argument('--jobs',type=int,default=4)
    args=ap.parse_args();os.chdir(ROOT);require_frozen();head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    out=Path(args.out)
    if out.is_absolute() or '..' in out.parts or out.parts[0]!='build':raise ValueError('输出为build新目录')
    out.mkdir(parents=True,exist_ok=False);v=out/'vectors';v.mkdir();cases=[];lens=[];bits=[];symbols=[];labels=[]
    def add(name,lengths,kind,seq=(),outcome=0,invalid=()):
        encoded=[x for s in seq for x in canonical(lengths)[s]] if not outcome else list(invalid)
        cases.append([len(lengths),kind,outcome,len(lens),len(encoded),len(bits),len(seq),len(symbols)])
        lens.extend(lengths);bits.extend(encoded);symbols.extend(seq);labels.append(name)
    add('four-symbols',[2]*4,0,[3,0,2,1,3,3,0])
    l=[0]*257;l[0]=1;l[256]=1;add('literal-eob',l,1,[0,256,0,0,256])
    l=[0]*257;l[256]=1;add('eob-single',l,1,[256,256,256])
    add('distance-single',[0,0,1,0],2,[2,2,2])
    add('empty-distance-use',[0],2,outcome=2,invalid=[0])
    add('oversubscribed',[1,1,1],0,outcome=1)
    add('incomplete-code-length',[1,0],0,outcome=1)
    l=[0]*257;l[0]=2;l[256]=2;add('incomplete-literal',l,1,outcome=1)
    l=[0]*257;l[0]=1;l[1]=1;add('missing-eob',l,1,outcome=1)
    add('deep-fifteen',list(range(1,15))+[15,15],0,list(range(16))*2)
    l=[8]*144+[9]*112+[7]*24+[8]*8
    add('rfc-fixed-literal',l,1,[0,143,144,255,256,279,280,287])
    add('rfc-fixed-distance',[5]*32,2,list(range(32)))
    for name,items in [('lengths.txt',lens),('bits.txt',bits),('symbols.txt',symbols)]:
        (v/name).write_text(''.join(str(x)+'\n' for x in items))
    (v/'cases.txt').write_text(''.join(' '.join(map(str,c))+'\n' for c in cases))
    rtl=out/'rtl'
    run(shlex.split(os.environ.get('MILL','mill'))+['-i','png.tool.runMain','openimage.png.EmitHuffman',rtl,v,'negative' if args.negative else 'normal'],out,'emit')
    files=[rtl/p for p in (rtl/'filelist.f').read_text().splitlines() if p.strip()]
    binds=sorted((rtl/'verification/assert').glob('layers-*-Verification-Assert.sv'));assert binds
    (out/'simulation.f').write_text('+incdir+'+str(ROOT/rtl/'verification')+'\n+incdir+'+str(ROOT/rtl/'verification/assert')+'\n'+''.join(str(ROOT/p)+'\n' for p in files+binds))
    product=[p for p in files if p.parent==rtl and p.name!='HuffmanSuite.sv']
    for p in product:assert '$readmem' not in p.read_text()
    run(['verilator','--lint-only','--assert','--top-module','CanonicalHuffman',*product],out,'product-lint')
    run(['verilator','--cc','--exe','--build','--assert','--top-module','HuffmanSuite','--Mdir',out/'obj','-j',args.jobs,
         '-CFLAGS','-O2 -std=c++17','-f',out/'simulation.f',ROOT/'sim/huffman_clock_main.cpp'],out,'verilator-build')
    p=subprocess.run([out/'obj/VHuffmanSuite'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    log=p.stdout.decode('utf-8',errors='replace');(out/'simulation.log').write_text(log)
    if args.negative:assert p.returncode!=0 and 'HUFFMAN_SYMBOL_MISMATCH' in log
    else:assert p.returncode==0 and 'HUFFMAN_SUITE_PASS' in log and log.count('HUFFMAN_CASE_PASS')==len(cases)
    require_frozen();assert head==subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    record={'status':0,'head':head,'cases':labels,'negative':args.negative,'threads_per_simulation':1,'inflate_complete':False}
    (out/'completion.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps(record))
if __name__=='__main__':main()
