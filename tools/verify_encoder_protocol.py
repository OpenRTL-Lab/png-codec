#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: verify_encoder_protocol.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""真实PNG编码接口错误、容量、恢复和复位；负控只能由指定断言拒绝。"""
import argparse
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess
import zlib
from verify_sample import ROOT,require_frozen,run

def png(raw,w=3,h=2,banked=False):
    def chunk(name,p):return struct.pack('>I',len(p))+name+p+struct.pack('>I',zlib.crc32(name+p))
    filtered=b''.join(b'\0'+raw[y*w:(y+1)*w] for y in range(h));n=len(filtered)
    stream=b'\x78\x01\x01'+struct.pack('<HH',n,n^65535)+filtered+struct.pack('>I',zlib.adler32(filtered))
    pieces=[stream] if not banked else [stream[:16]]+[stream[i:i+2048] for i in range(16,len(stream),2048)]
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,0,0,0,0))+b''.join(chunk(b'IDAT',p) for p in pieces)+chunk(b'IEND',b'')

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',required=True);ap.add_argument('--jobs',type=int,default=4)
    ap.add_argument('--banked-v120',action='store_true',help='明确采用first16/then2048分块的独立STORED答案，旧默认不变')
    ap.add_argument('--negative',choices=['pixel','status','tag']);args=ap.parse_args();os.chdir(ROOT);require_frozen()
    head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip();out=Path(args.out)
    if out.is_absolute() or '..' in out.parts or out.parts[0]!='build':raise ValueError('输出为build新目录')
    out.mkdir(parents=True,exist_ok=False);v=out/'vectors';v.mkdir();beats=[];cases=[];gold=[];labels=[]
    raw=bytes([1,2,3,4,5,6]);answer=png(raw,banked=args.banked_v120)
    assert len(answer)==(88 if args.banked_v120 else 76)
    def stream(b):return [(int.from_bytes(b[i:i+8],'little'),(1<<len(b[i:i+8]))-1,int(i+8>=len(b))) for i in range(0,len(b),8)]
    def add(label,w=3,h=2,t=0,b=8,n=6,limit=10000,meta=b'',plen=0,tlen=0,status=0,received=None,ib=None,mb=None,abort=0):
        ins=stream(raw) if ib is None else ib;metas=stream(meta) if mb is None else mb
        ia=len(beats);beats.extend(ins);ma=len(beats);beats.extend(metas)
        go=len(gold)
        if not status and not abort:gold.extend(answer)
        cases.append([w,h,t,b,n,limit,plen,tlen,status,n if received is None else received,abort,len(ins),len(metas),ia,ma,go,len(answer) if not status and not abort else 0]);labels.append(label)
    add('exact-capacity',limit=len(answer))
    add('capacity-minus-one',limit=len(answer)-1,status=6)
    add('capacity-first-partial',limit=1,status=6)
    add('bad-command',w=0,status=1,received=0,ib=[])
    add('zero-keep',status=5,received=0,ib=[(0,0,1)])
    add('non-contiguous-keep',status=5,received=2,ib=[(0,5,1)])
    add('early-last',status=5,received=3,ib=[(0,7,1)])
    add('missing-last-at-length',status=5,received=6,ib=[(0,63,0)])
    add('excess-input-beat',status=5,received=8,ib=[(0,255,1)])
    add('short-non-final-drain',h=3,n=9,status=5,received=9,ib=[(0,7,0),(0,63,1)])
    add('low-depth-tail',w=3,h=1,b=1,n=1,status=3,ib=[(1,1,1)])
    add('index-out-of-range',w=3,h=1,t=3,b=2,n=1,meta=bytes(6),plen=6,status=3,ib=[(192,1,1)])
    add('gray-transparent-high-bits',meta=b'\x01\x00',tlen=2,status=3)
    add('rgb-transparent-high-bits',w=1,h=2,t=2,meta=b'\x01\0\0\0\0\0',tlen=6,status=3)
    add('metadata-missing-last',t=3,plen=6,status=5,mb=[(0,63,0)],ib=[(0,63,1)])
    add('metadata-early-last',t=3,plen=6,status=5,mb=[(0,7,1)],ib=[(0,63,1)])
    add('reset-stalled-valid',abort=1)
    for stage in range(4):add('reset-command-stage-'+str(stage),abort=2+stage)
    for label,config in [
        ('bad-height',{'h':8193}),('bad-width',{'w':8193}),('bad-color',{'t':1}),
        ('bad-depth',{'b':3}),('bad-raw-length',{'n':7}),('bad-limit-zero',{'limit':0}),
        ('bad-limit-high',{'limit':0x40000001}),('bad-input-zero',{'n':0}),
        ('bad-input-high',{'n':0x40000001}),('bad-palette-divisibility',{'t':3,'plen':5}),
        ('bad-palette-too-large',{'t':3,'plen':771}),('bad-palette-missing',{'t':3}),
        ('bad-palette-depth-capacity',{'t':3,'b':1,'plen':9}),
        ('bad-trns-count',{'tlen':1}),('bad-alpha-trns',{'t':4,'n':12,'tlen':2})]:
        add(label,status=1,received=0,ib=[],**config)
    add('good-after-all-errors')
    (v/'cases.txt').write_text(''.join(' '.join(map(str,c))+'\n' for c in cases))
    (v/'beats.txt').write_text(''.join(' '.join(map(str,c))+'\n' for c in beats))
    (v/'gold.txt').write_text(''.join(str(x)+'\n' for x in gold))
    rtl=out/'rtl'
    run(shlex.split(os.environ.get('MILL','mill'))+['-i','png.tool.runMain','openimage.png.EmitEncoderProtocol',rtl,v,args.negative or 'none'],out,'emit')
    files=[rtl/p for p in (rtl/'filelist.f').read_text().splitlines() if p.strip()]
    # Qualified products bring their original full bind list at Verilator compilation.
    binds=sorted((rtl/'verification/assert').glob('layers-*-Verification-Assert.sv'));assert binds
    (out/'simulation.f').write_text('+incdir+'+str(ROOT/rtl/'verification')+'\n+incdir+'+str(ROOT/rtl/'verification/assert')+'\n'+''.join(str(ROOT/p)+'\n' for p in files+binds))
    run(['verilator','--cc','--exe','--build','--assert','--top-module','EncoderProtocolSuite','--Mdir',out/'obj','-j',args.jobs,
         '-CFLAGS','-O2 -std=c++17','-f',out/'simulation.f',ROOT/'sim/encoder_protocol_clock_main.cpp'],out,'verilator-build')
    result=subprocess.run([out/'obj/VEncoderProtocolSuite'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    log=result.stdout.decode('utf-8',errors='replace');(out/'simulation.log').write_text(log)
    if args.negative:
        marker={'pixel':'PNG_PROTOCOL_BYTE_MISMATCH','status':'PNG_PROTOCOL_STATUS','tag':'PNG_PROTOCOL_TAG'}[args.negative]
        assert result.returncode!=0 and marker in log,'负控未被指定检查拒绝'
    else:assert result.returncode==0 and 'PNG_PROTOCOL_SUITE_PASS' in log and log.count('PNG_PROTOCOL_CASE_PASS')==len(cases)
    require_frozen();assert head==subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    record={'status':0,'head':head,'cases':labels,'negative':args.negative,'threads_per_simulation':1,'product_complete':False,
            'independent_banked_layout':args.banked_v120,'exact_output_capacity':len(answer)}
    (out/'completion.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps(record))
if __name__=='__main__':main()
