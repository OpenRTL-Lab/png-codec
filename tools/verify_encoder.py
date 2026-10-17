#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: verify_encoder.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""150组合真实PNG编码，独立libpng/块与token检查；参考仅离线。"""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import struct
import subprocess
import zlib
from inspect_deflate import inspect
from verify_sample import ROOT, require_frozen, run

FORMATS=[(0,b) for b in (1,2,4,8,16)]+[(2,8),(2,16)]+[(3,b) for b in (1,2,4,8)]+[(4,8),(4,16),(6,8),(6,16)]

def boundary_choices():
    return ([(0,8,3,1,1,8191),(0,8,4,0,1,8192),(0,1,4,1,8192,1)]+
            [(0,1,i%5,1,w,1) for i,w in enumerate((7,8,9,63,64,65))]+
            [(3,8,4,1,17,3),(3,8,3,0,17,3),(2,8,4,1,17,3),(6,8,3,1,17,3),(0,8,4,0,17,3)])

def boundary_profile(index):
    return {9:'min-palette',10:'short-alpha',11:'no-metadata',12:'no-metadata',13:'no-metadata'}.get(index)

def check_file(data,cfg,raw,metadata):
    assert data[:8]==b'\x89PNG\r\n\x1a\n'
    pos=8;chunks=[];compressed=bytearray();names=[]
    while pos<len(data):
        n=struct.unpack('>I',data[pos:pos+4])[0];name=data[pos+4:pos+8]
        p=data[pos+8:pos+8+n];crc=struct.unpack('>I',data[pos+8+n:pos+12+n])[0]
        assert zlib.crc32(name+p)==crc
        names.append(name);chunks.append((name,p));pos+=12+n
        if name==b'IDAT':assert 0<n<=32768;compressed+=p
    w,h,t,b,f,fixed,_,_,plen,tlen,_=cfg
    expected=[b'IHDR']+([b'PLTE'] if plen else [])+([b'tRNS'] if tlen else [])
    assert names[:len(expected)]==expected and names[-1]==b'IEND'
    assert all(n==b'IDAT' for n in names[len(expected):-1]) and b'IDAT' in names
    assert chunks[0][1]==struct.pack('>IIBBBBB',w,h,b,t,0,0,0) and chunks[-1][1]==b''
    values=dict(chunks)
    if plen:assert values[b'PLTE']==metadata[:plen]
    if tlen:assert values[b'tRNS']==metadata[plen:]
    c={0:1,2:3,3:1,4:2,6:4}[t];r=(w*c*b+7)//8
    filtered=zlib.decompress(compressed);assert len(filtered)==h*(r+1)
    assert all(filtered[y*(r+1)]==f for y in range(h))
    tokens=inspect(bytes(compressed),filtered,bool(fixed))
    return {'idat_chunks':names.count(b'IDAT'),'zlib_bytes':len(compressed),'matches':tokens['matches']}

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',required=True)
    ap.add_argument('--jobs',type=int,default=4);ap.add_argument('--limit',type=int)
    ap.add_argument('--semantic-cases',action='store_true',help='补全索引默认alpha和8/16位所有alpha取值')
    ap.add_argument('--boundary-cases',action='store_true',help='窄长图/位拍边界/最小调色板和短alpha表')
    ap.add_argument('--reuse-emission');ap.add_argument('--emission-commit')
    args=ap.parse_args();os.chdir(ROOT);require_frozen()
    head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    out=Path(args.out)
    if out.is_absolute() or '..' in out.parts or out.parts[0]!='build':raise ValueError('输出为build新目录')
    out.mkdir(parents=True,exist_ok=False);prefix=ROOT/'dependencies/install'
    pnglib=next(p for p in [prefix/'lib64/libpng16.a',prefix/'lib/libpng16.a'] if p.is_file())
    libs=[pnglib,prefix/'lib/libz.a','-lm']
    for name,source in [('generator','row_reference.c'),('checker','encoder_check.c')]:
        run([os.environ.get('CC','cc'),'-O2','-std=c11','-Wall','-Wextra','-Werror','-I'+str(prefix/'include'),
             ROOT/'reference'/source,*libs,'-o',out/name],out,name+'-compile')
    vectors=out/'vectors';vectors.mkdir();raw_all=bytearray();meta_all=bytearray();cases=[]
    choices=[(t,b,f,m,17,3) for t,b in FORMATS for f in range(5) for m in range(2)]
    choices += [(6,16,4,0,8192,2),(2,16,1,1,8191,1)]
    if args.semantic_cases:choices=[(3,8,4,1,256,2),(4,8,3,1,256,1),(6,8,2,0,256,1),(4,16,1,1,256,256),(6,16,4,1,256,256)]
    if args.boundary_cases:
        assert not args.semantic_cases
        choices=boundary_choices()
    if args.limit:choices=choices[:args.limit]
    for index,(t,b,f,m,w,h) in enumerate(choices):
        v=vectors/f'case-{index:03d}';v.mkdir()
        run([out/'generator','generate',v,t,b,w,h,f,0],out,f'generate-{index:03d}')
        raw=bytes.fromhex((v/'raw.hex').read_text())
        profile=boundary_profile(index) if args.boundary_cases else None
        if profile=='min-palette':raw=bytes(len(raw))
        if args.semantic_cases and t in (4,6):
            data=bytearray(raw);channels=2 if t==4 else 4;size=b//8
            for p in range(w*h):
                start=(p*channels+channels-1)*size
                data[start:start+size]=(p&((1<<b)-1)).to_bytes(size,'big')
            raw=bytes(data)
        (v/'raw.bin').write_bytes(raw)
        plen=3*(1<<b) if t==3 else 6 if t in (2,6) else 0
        if profile=='min-palette':plen=3
        if profile=='no-metadata':plen=0
        palette=bytes(v for i in range(plen//3) for v in (i,255-i,(i*37)&255))
        if t==3:transparency=bytes((i*29)&255 for i in range(max(1,(1<<b)-1)))
        elif t==0:
            val=raw[0]>>(8-b) if b<8 else raw[0] if b==8 else int.from_bytes(raw[:2],'big')
            transparency=val.to_bytes(2,'big')
        elif t==2:
            transparency=b''.join((raw[i] if b==8 else int.from_bytes(raw[2*i:2*i+2],'big')).to_bytes(2,'big') for i in range(3))
        else:transparency=b''
        if profile in ('min-palette','short-alpha'):transparency=b'\x00'
        if profile=='no-metadata':transparency=b''
        metadata=palette+transparency;(v/'meta.bin').write_bytes(metadata)
        cases.append([w,h,t,b,f,m,len(raw),len(raw_all),plen,len(transparency),len(meta_all)])
        raw_all+=raw;meta_all+=metadata
    (vectors/'raw.hex').write_text(''.join(f'{b:02x}\n' for b in raw_all))
    (vectors/'meta.hex').write_text(''.join(f'{b:02x}\n' for b in meta_all) or '00\n')
    (vectors/'cases.txt').write_text(''.join(' '.join(map(str,c))+'\n' for c in cases))
    rtl=out/'rtl'
    if args.reuse_emission:
        if not args.emission_commit:raise ValueError('复用须声明提交')
        previous=Path(args.reuse_emission)
        for name in ('raw.hex','meta.hex','cases.txt'):assert (previous/'vectors'/name).read_bytes()==(vectors/name).read_bytes()
        assert not subprocess.check_output(['git','diff',args.emission_commit,'HEAD','--','src','build.mill'])
        rtl=previous/'rtl'
    else:
        run(shlex.split(os.environ.get('MILL','mill'))+['-i','png.tool.runMain','openimage.png.EmitEncoder',rtl,vectors],out,'emit')
    files=[rtl/p for p in (rtl/'filelist.f').read_text().splitlines() if p.strip()]
    binds=sorted((rtl/'verification/assert').glob('layers-*-Verification-Assert.sv'));assert binds
    (out/'simulation.f').write_text('+define+ENABLE_INITIAL_MEM_\n'+
        '+incdir+'+str(ROOT/rtl/'verification')+'\n+incdir+'+str(ROOT/rtl/'verification/assert')+'\n'+
        ''.join(str(ROOT/p)+'\n' for p in files+binds))
    # 从实际模块实例递归取产品闭包，不以meta/raw前缀误删产品存储。
    from verify_inflate import product_closure
    product=product_closure(rtl,'PngEncoder')
    for p in product:assert '$readmem' not in p.read_text(),f'验证ROM进入产品闭包：{p}'
    run(['verilator','--lint-only','--assert','--top-module','PngEncoder',*product],out,'product-lint')
    run(['verilator','--cc','--exe','--build','--assert','--top-module','EncoderSuite',
         '--Mdir',out/'obj','-j',args.jobs,'-CFLAGS','-O2 -std=c++17','-f',out/'simulation.f',
         ROOT/'sim/encoder_clock_main.cpp'],out,'verilator-build')
    run([out/'obj/VEncoderSuite'],out,'simulation')
    log=(out/'simulation.log').read_text();assert 'PNG_ENCODER_SUITE_PASS' in log
    assert log.count('PNG_ENCODER_CASE_PASS')==len(cases)
    actual={i:bytearray() for i in range(len(cases))};finished=set()
    for task,data,keep,last in re.findall(r'PNG_DATA\s+(\d+)\s+([0-9a-fA-F]+)\s+([0-9a-fA-F]+)\s+([01])',log):
        t=int(task);d=int(data,16);k=int(keep,16);assert t in actual and t not in finished
        actual[t].extend((d>>(8*i))&255 for i in range(8) if k&(1<<i))
        if last=='1':finished.add(t)
    assert len(finished)==len(cases)
    records=[]
    for i,cfg in enumerate(cases):
        v=vectors/f'case-{i:03d}';png=out/f'encoded-{i:03d}.png';png.write_bytes(actual[i])
        w,h,t,b,_,_,_,_,plen,tlen,_=cfg
        run([out/'checker',png,v/'raw.bin',v/'meta.bin',w,h,t,b,plen,tlen],out,f'check-{i:03d}')
        records.append({'case':i,'config':cfg,'output_bytes':len(actual[i]),**check_file(bytes(actual[i]),cfg,(v/'raw.bin').read_bytes(),(v/'meta.bin').read_bytes())})
    require_frozen();assert head==subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    result={'status':0,'head':head,'tasks':records,'encoder_base_matrix':len(cases)>=150,
            'decoder_complete':False,'threads_per_simulation':1,'emission_commit':args.emission_commit}
    (out/'completion.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k!='tasks'}))
if __name__=='__main__':main()
