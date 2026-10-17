#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: verify_rows.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""五滤波/逆滤波真实RTL矩阵；参考PNG先由独立libpng解码核对。"""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
from verify_sample import ROOT,require_frozen,run

FORMATS=[(0,b) for b in (1,2,4,8,16)]+[(2,b) for b in (8,16)]+\
        [(3,b) for b in (1,2,4,8)]+[(4,b) for b in (8,16)]+[(6,b) for b in (8,16)]


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out',required=True)
    ap.add_argument('--jobs',type=int,default=4)
    ap.add_argument('--negative',action='store_true')
    ap.add_argument('--continuous',action='store_true',help='逐周期供给/接收并断言N字节在N+1周期完成')
    ap.add_argument('--reuse-emission')
    ap.add_argument('--emission-commit')
    args=ap.parse_args();os.chdir(ROOT);require_frozen()
    out=Path(args.out)
    if out.is_absolute() or '..' in out.parts or out.parts[0]!='build':
        raise ValueError('输出必须是build内新目录')
    out.mkdir(parents=True,exist_ok=False)
    prefix=ROOT/'dependencies/install'
    library=next(prefix/d/'libpng16.a' for d in ('lib','lib64') if (prefix/d/'libpng16.a').is_file())
    run([os.environ.get('CC','cc'),'-O2','-std=c11','-Wall','-Wextra','-Werror',
         '-I'+str(prefix/'include'),ROOT/'reference/row_reference.c',library,prefix/'lib/libz.a',
         '-lm','-o',out/'reference'],out,'reference-compile')
    vectors=out/'vectors';vectors.mkdir()
    records=[];offset=0
    # 每种位深五固定滤波，再补逐行混合及65536字节最大行。
    specs=[(17,9,t,b,f,0) for t,b in FORMATS for f in range(5)]
    specs += [(1,7,t,b,0,1) for t,b in FORMATS]
    specs += [(8192,2,6,16,4,1)]
    if args.negative:specs=specs[:1]
    packed={name:bytearray() for name in ('raw.hex','filtered.hex','filters.hex')}
    configs=[]
    for i,(w,h,t,b,f,mixed) in enumerate(specs):
        case=out/f'case-{i:03d}';case.mkdir()
        run([out/'reference','generate',case,t,b,w,h,f,mixed],out,f'reference-{i:03d}')
        cfg=json.loads((case/'config.json').read_text());configs.append(cfg)
        records.append(f'{cfg["row_bytes"]} {h} {cfg["bpp"]} {offset}\n')
        offset+=cfg['row_bytes']*h
        for name in packed:packed[name].extend((case/name).read_bytes())
    for name,data in packed.items():(vectors/name).write_bytes(data)
    (vectors/'cases.txt').write_text(''.join(records))
    rtl=out/'rtl'
    if args.reuse_emission:
        if not args.emission_commit or args.negative or args.continuous:raise ValueError('复用需声明提交且不改变负控/连续模式')
        previous=Path(args.reuse_emission)
        for name in (*packed,'cases.txt'):
            assert (previous/'vectors'/name).read_bytes()==(vectors/name).read_bytes()
        assert not subprocess.check_output(['git','diff',args.emission_commit,'HEAD','--','src','build.mill'])
        rtl=previous/'rtl'
    else:
        run(shlex.split(os.environ.get('MILL','mill'))+['-i','png.tool.runMain','openimage.png.EmitRows',
            rtl,vectors,int(args.negative),*(['continuous'] if args.continuous else [])],out,'emit')
    files=[rtl/p for p in (rtl/'filelist.f').read_text().splitlines() if p.strip()]
    bindings=sorted((rtl/'verification/assert').glob('layers-*-Verification-Assert.sv'))
    assert len(bindings)==2
    (out/'simulation.f').write_text('+define+ENABLE_INITIAL_MEM_\n'+
        '+incdir+'+str(ROOT/rtl/'verification')+'\n'+
        '+incdir+'+str(ROOT/rtl/'verification/assert')+'\n'+
        ''.join(str(ROOT/p)+'\n' for p in files+bindings))
    product=[rtl/'PngRowTransform.sv']+sorted(rtl.glob('line_*.sv'))
    assert len(product)==2 and all(p.is_file() and '$readmem' not in p.read_text() for p in product)
    (out/'product.f').write_text(''.join(str(ROOT/p)+'\n' for p in product))
    run(['verilator','--lint-only','--assert','--top-module','PngRowTransform','-f',out/'product.f'],out,'product-lint')
    run(['verilator','--cc','--exe','--build','--assert','--top-module','RowTransformSuite',
        '--Mdir',out/'obj','-j',args.jobs,'-CFLAGS','-O2 -std=c++17',
        '-f',out/'simulation.f',ROOT/'sim/row_clock_main.cpp'],out,'verilator-build')
    with (out/'simulation.log').open('xb') as log:
        p=subprocess.run([str(out/'obj/VRowTransformSuite')],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
    text=(out/'simulation.log').read_text(errors='replace')
    if args.negative:
        assert p.returncode!=0 and 'ROW_BYTE_MISMATCH' in text
    else:
        assert p.returncode==0 and 'ROW_SUITE_PASS' in text
        assert text.count('ROW_CASE_PASS')==2*len(specs)
        if args.continuous:assert text.count('ROW_RATE_EVENT')==2*len(specs)
    record={'status':0,'component':'five-filters-and-inverse','negative':args.negative,
            'cases':configs,'rtl_tasks':2*len(specs),'bytes':offset*2,'threads_per_simulation':1,
            'head':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
            'emission_root':str(rtl),'emission_commit':args.emission_commit,
            'continuous_rate_checked':args.continuous,
            'full_png_product_complete':False}
    (out/'completion.json').write_text(json.dumps(record,indent=2)+'\n')
    print(json.dumps({k:v for k,v in record.items() if k!='cases'}))


if __name__=='__main__':main()
