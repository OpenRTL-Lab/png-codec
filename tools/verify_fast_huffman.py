#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: verify_fast_huffman.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""新根表/完整长码后备的独立规范码、背压、部分位池及连续速率验证。"""
import argparse
import json
import os
from pathlib import Path
import re
import resource
import shlex
import subprocess
from verify_huffman import canonical
from verify_inflate import product_closure
from verify_sample import ROOT,require_frozen,run


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out',required=True);ap.add_argument('--jobs',type=int,default=1)
    ap.add_argument('--negative',action='store_true')
    ap.add_argument('--parallel-long',action='store_true');args=ap.parse_args()
    os.chdir(ROOT);require_frozen();resource.setrlimit(resource.RLIMIT_CORE,(0,0))
    head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    out=Path(args.out);assert out.parts[0]=='build' and '..' not in out.parts and not out.is_absolute()
    out.mkdir(exist_ok=False);v=out/'vectors';v.mkdir();cases=[];lens=[];bits=[];symbols=[];labels=[]
    def add(name,lengths,kind,seq=(),outcome=0,invalid=(),ideal=False):
        encoded=[x for s in seq for x in canonical(lengths)[s]] if not outcome else list(invalid)
        cases.append([len(lengths),kind,outcome,len(lens),len(encoded),len(bits),len(seq),len(symbols),int(ideal)])
        lens.extend(lengths);bits.extend(encoded);symbols.extend(seq);labels.append(name)
    add('short-root-continuous',[2]*4,0,list(range(4))*64,ideal=True)
    add('short-root-partial-pool',[2]*4,0,[3,0,2,1,3,3,0]*10)
    l=[0]*257;l[0]=1;l[256]=1;add('literal-eob',l,1,[0,256,0,0,256]*4)
    l=[0]*257;l[256]=1;add('eob-single',l,1,[256]*8)
    add('distance-single',[0,0,1,0],2,[2]*7)
    add('empty-distance-use',[0],2,outcome=2,invalid=[0])
    add('oversubscribed',[1,1,1],0,outcome=1)
    add('incomplete-code-length',[1,0],0,outcome=1)
    l=[0]*257;l[0]=2;l[256]=2;add('incomplete-literal',l,1,outcome=1)
    l=[0]*257;l[0]=1;l[1]=1;add('missing-eob',l,1,outcome=1)
    add('deep-fifteen',list(range(1,15))+[15,15],0,list(range(16))*3)
    l=[8]*144+[9]*112+[7]*24+[8]*8
    add('rfc-fixed-literal',l,1,[0,143,144,255,256,279,280,287]*4)
    add('rfc-fixed-distance',[5]*32,2,list(range(32)))
    add('invalid-single-prefix',[0,0,1,0],2,outcome=2,invalid=[1])
    if args.parallel_long:add('fifteen-bit-continuous',list(range(1,15))+[15,15],0,[15]*256,ideal=True)
    for name,items in [('lengths.txt',lens),('bits.txt',bits),('symbols.txt',symbols)]:
        (v/name).write_text(''.join(str(x)+'\n' for x in items))
    (v/'cases.txt').write_text(''.join(' '.join(map(str,c))+'\n' for c in cases))
    rtl=out/'rtl'
    run(shlex.split(os.environ.get('MILL','mill'))+['-i','png.tool.runMain','openimage.png.EmitFastHuffman',rtl,v,'negative' if args.negative else 'normal',*(['parallel'] if args.parallel_long else [])],out,'emit')
    files=[rtl/p for p in (rtl/'filelist.f').read_text().splitlines() if p.strip()]
    binds=sorted((rtl/'verification/assert').glob('layers-*-Verification-Assert.sv'));assert binds
    (out/'simulation.f').write_text('+incdir+'+str(ROOT/rtl/'verification')+'\n+incdir+'+str(ROOT/rtl/'verification/assert')+'\n'+''.join(str(ROOT/p)+'\n' for p in files+binds))
    product=product_closure(rtl,'FastCanonicalHuffman')
    assert all('$readmem' not in p.read_text() for p in product)
    run(['verilator','--lint-only','--assert','--top-module','FastCanonicalHuffman',*product],out,'product-lint')
    run(['verilator','--cc','--exe','--build','--assert','--top-module','FastHuffmanSuite','--Mdir',out/'obj','-j',args.jobs,
         '-CFLAGS','-O2 -std=c++17','-f',out/'simulation.f',ROOT/'sim/fast_huffman_clock_main.cpp'],out,'verilator-build')
    p=subprocess.run([out/'obj/VFastHuffmanSuite'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    log=p.stdout.decode('utf-8',errors='replace');(out/'simulation.log').write_text(log)
    if args.negative:assert p.returncode!=0 and 'FAST_HUFFMAN_SYMBOL_MISMATCH' in log
    else:assert p.returncode==0 and 'FAST_HUFFMAN_SUITE_PASS' in log and log.count('FAST_HUFFMAN_CASE_PASS')==len(cases)
    (out/'simulation-exit.json').write_text(json.dumps({'returncode':p.returncode})+'\n')
    require_frozen();assert head==subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    record={'status':0,'head':head,'cases':labels,'negative':args.negative,'threads_per_simulation':1,
            'continuous_symbols':256,'root_bits':9,'max_code_bits':15,'png_integration':False,
            'parallel_long':args.parallel_long,'long_continuous_symbols':256 if args.parallel_long else 0}
    (out/'completion.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps(record))


if __name__=='__main__':main()
