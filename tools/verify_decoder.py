#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: verify_decoder.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""固定libpng完整文件→真实PNG解码RTL，原位深/索引/元数据逐字节核对。"""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
from verify_encoder import FORMATS,boundary_choices,boundary_profile
from verify_inflate import product_closure
from verify_sample import ROOT,require_frozen,run


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out',required=True);ap.add_argument('--jobs',type=int,default=4)
    ap.add_argument('--limit',type=int);ap.add_argument('--negative',action='store_true')
    ap.add_argument('--semantic-cases',action='store_true',help='补全索引默认alpha和8/16位所有alpha取值')
    ap.add_argument('--boundary-cases',action='store_true')
    ap.add_argument('--reuse-emission');ap.add_argument('--emission-commit')
    args=ap.parse_args();os.chdir(ROOT);require_frozen();head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    out=Path(args.out)
    if out.is_absolute() or '..' in out.parts or out.parts[0]!='build':raise ValueError('输出为build新目录')
    out.mkdir(parents=True,exist_ok=False);v=out/'vectors';v.mkdir();prefix=ROOT/'dependencies/install'
    pnglib=next(p for p in [prefix/'lib64/libpng16.a',prefix/'lib/libpng16.a'] if p.is_file());libs=[pnglib,prefix/'lib/libz.a','-lm']
    for name,source in [('generator','decoder_reference.c'),('checker','encoder_check.c')]:
        run([os.environ.get('CC','cc'),'-O2','-std=c11','-Wall','-Wextra','-Werror','-I'+str(prefix/'include'),ROOT/'reference'/source,*libs,'-o',out/name],out,name+'-compile')
    choices=[(t,b,f,m,17,5) for t,b in FORMATS for f in range(5) for m in range(3)]
    choices.extend([(6,16,5,2,8192,2),(2,16,4,1,8191,1),(0,1,0,0,1,1)])
    if args.semantic_cases:choices=[(3,8,4,2,256,2),(4,8,3,1,256,1),(6,8,2,0,256,1),(4,16,1,2,256,256),(6,16,4,2,256,256)]
    if args.boundary_cases:
        assert not args.semantic_cases
        choices=boundary_choices()
    if args.limit:choices=choices[:args.limit]
    cases=[];records=[];png_all=bytearray();raw_all=bytearray();meta_all=bytearray()
    for i,(t,b,f,m,w,h) in enumerate(choices):
        case=v/f'case-{i:03d}';case.mkdir()
        profile=boundary_profile(i) if args.boundary_cases else 'alpha' if args.semantic_cases else None
        run([out/'generator','generate',case,t,b,w,h,f,m]+([profile] if profile else []),out,f'generate-{i:03d}')
        raw=(case/'raw.bin').read_bytes();meta=(case/'meta.bin').read_bytes();png=(case/'software.png').read_bytes()
        plen=3*(1<<b) if t==3 else 6 if t in (2,6) else 0
        tlen=max(1,(1<<b)-1) if t==3 else 2 if t==0 else 6 if t==2 else 0
        if profile=='min-palette':plen=3;tlen=1
        if profile=='short-alpha':tlen=1
        if profile=='no-metadata':plen=0;tlen=0
        run([out/'checker',case/'software.png',case/'raw.bin',case/'meta.bin',w,h,t,b,plen,tlen],out,f'reference-check-{i:03d}')
        cases.append([w,h,t,b,len(png),len(png_all),len(raw),len(raw_all),plen,tlen,len(meta_all)])
        records.append({'format':[t,b],'filter':f,'mode':m,'width':w,'height':h,'alpha_pattern':args.semantic_cases})
        png_all+=png;raw_all+=raw;meta_all+=meta
    for name,data in [('png.hex',png_all),('raw.hex',raw_all),('meta.hex',meta_all)]:
        (v/name).write_text(''.join(f'{b:02x}\n' for b in data) or '00\n')
    (v/'cases.txt').write_text(''.join(' '.join(map(str,c))+'\n' for c in cases))
    rtl=out/'rtl'
    if args.reuse_emission:
        assert args.emission_commit
        previous=Path(args.reuse_emission)
        for name in ('png.hex','raw.hex','meta.hex','cases.txt'):assert (previous/'vectors'/name).read_bytes()==(v/name).read_bytes()
        assert not subprocess.check_output(['git','diff',args.emission_commit,'HEAD','--','src','build.mill'])
        rtl=previous/'rtl'
    else:
        run(shlex.split(os.environ.get('MILL','mill'))+['-i','png.tool.runMain','openimage.png.EmitDecoder',rtl,v,'negative' if args.negative else 'normal'],out,'emit')
    os.environ['CCACHE_DISABLE']='1'
    files=[rtl/p for p in (rtl/'filelist.f').read_text().splitlines() if p.strip()]
    binds=sorted((rtl/'verification/assert').glob('layers-*-Verification-Assert.sv'));assert binds
    (out/'simulation.f').write_text('+define+ENABLE_INITIAL_MEM_\n+incdir+'+str(ROOT/rtl/'verification')+'\n+incdir+'+str(ROOT/rtl/'verification/assert')+'\n'+''.join(str(ROOT/p)+'\n' for p in files+binds))
    product=product_closure(rtl,'PngDecoder')
    for p in product:assert '$readmem' not in p.read_text()
    run(['verilator','--lint-only','--assert','--top-module','PngDecoder',*product],out,'product-lint')
    run(['verilator','--cc','--exe','--build','--assert','--top-module','DecoderSuite','--Mdir',out/'obj','-j',args.jobs,
         '-CFLAGS','-O2 -std=c++17','-f',out/'simulation.f',ROOT/'sim/decoder_clock_main.cpp'],out,'verilator-build')
    p=subprocess.run([out/'obj/VDecoderSuite'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    log=p.stdout.decode('utf-8',errors='replace');(out/'simulation.log').write_text(log)
    (out/'simulation-exit.json').write_text(json.dumps({'returncode':p.returncode,'log_bytes':len(p.stdout)})+'\n')
    if args.negative:assert p.returncode!=0 and 'PNG_DECODER_BYTE_MISMATCH' in log
    else:assert p.returncode==0 and 'PNG_DECODER_SUITE_PASS' in log and log.count('PNG_DECODER_CASE_PASS')==len(cases)
    require_frozen();assert head==subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    result={'status':0,'head':head,'tasks':records,'negative':args.negative,'threads_per_simulation':1,
            'reference':'libpng1.6.59/zlib1.3.2','errors_and_full_capacity_complete':False,
            'emission_commit':args.emission_commit,'emission_root':str(rtl)}
    (out/'completion.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({k:v for k,v in result.items() if k!='tasks'}))
if __name__=='__main__':main()
