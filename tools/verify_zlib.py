#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: verify_zlib.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""真实RTL编码→固定zlib离线解码；另解析RFC token检查模式和实际匹配。"""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
from inspect_deflate import inspect
from verify_sample import ROOT,require_frozen,run


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out',required=True)
    ap.add_argument('--jobs',type=int,default=4)
    ap.add_argument('--wide',action='store_true')
    ap.add_argument('--continuous',action='store_true')
    ap.add_argument('--reuse-emission')
    ap.add_argument('--emission-commit')
    args=ap.parse_args();os.chdir(ROOT);require_frozen()
    head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    out=Path(args.out)
    if out.is_absolute() or '..' in out.parts or out.parts[0]!='build':raise ValueError('输出必须是build新目录')
    out.mkdir(parents=True,exist_ok=False)
    prefix=ROOT/'dependencies/install'
    run([os.environ.get('CC','cc'),'-O2','-std=c11','-Wall','-Wextra','-Werror',
        '-I'+str(prefix/'include'),ROOT/'reference/zlib_reference.c',prefix/'lib/libz.a',
        '-o',out/'reference'],out,'reference-compile')
    vectors=out/'vectors';vectors.mkdir()
    run([out/'reference','generate',vectors],out,'reference-generate')
    cases=[tuple(map(int,l.split())) for l in (vectors/'cases.txt').read_text().splitlines()]
    rtl=out/'rtl'
    if args.reuse_emission:
        if not args.emission_commit or args.wide or args.continuous:raise ValueError('复用必须声明提交且保持原配置')
        previous=Path(args.reuse_emission)
        for name in ('raw.hex','cases.txt'):
            assert (previous/'vectors'/name).read_bytes()==(vectors/name).read_bytes()
        assert not subprocess.check_output(['git','diff',args.emission_commit,'HEAD','--','src','build.mill'])
        rtl=previous/'rtl'
    else:
        run(shlex.split(os.environ.get('MILL','mill'))+['-i','png.tool.runMain','openimage.png.EmitZlib',
            rtl,vectors,*(['wide'] if args.wide else []),*(['continuous'] if args.continuous else [])],out,'emit')
    files=[rtl/p for p in (rtl/'filelist.f').read_text().splitlines() if p.strip()]
    bindings=sorted((rtl/'verification/assert').glob('layers-*-Verification-Assert.sv'))
    assert len(bindings)>=2
    (out/'simulation.f').write_text('+define+ENABLE_INITIAL_MEM_\n'+
        '+incdir+'+str(ROOT/rtl/'verification')+'\n'+
        '+incdir+'+str(ROOT/rtl/'verification/assert')+'\n'+
        ''.join(str(ROOT/p)+'\n' for p in files+bindings))
    module='WideZlibEncoder' if args.wide else 'ZlibEncoder'
    product=rtl/(module+'.sv')
    assert product.is_file() and '$readmem' not in product.read_text()
    run(['verilator','--lint-only','--assert','--top-module',module,product],out,'product-lint')
    run(['verilator','--cc','--exe','--build','--assert','--top-module','ZlibSuite',
        '--Mdir',out/'obj','-j',args.jobs,'-CFLAGS','-O2 -std=c++17',
        '-f',out/'simulation.f',ROOT/'sim/zlib_clock_main.cpp'],out,'verilator-build')
    run([out/'obj/VZlibSuite'],out,'simulation')
    text=(out/'simulation.log').read_text()
    assert 'ZLIB_SUITE_PASS' in text and text.count('ZLIB_CASE_PASS')==2*len(cases)
    actual={i:bytearray() for i in range(2*len(cases))};finished=set()
    for task,data,keep,last in re.findall(r'ZLIB_DATA\s+(\d+)\s+([0-9a-fA-F]+)\s+([0-9a-fA-F]+)\s+([01])',text):
        t=int(task);d=int(data,16);k=int(keep,16)
        assert t in actual and t not in finished
        actual[t].extend((d>>(8*i))&255 for i in range(8) if k&(1<<i))
        if last=='1':finished.add(t)
    assert len(finished)==2*len(cases)
    records=[];seen_lengths=set()
    for task,data in actual.items():
        raw=vectors/f'raw-{task//2:02d}.bin'
        encoded=out/f'encoded-{task:02d}.zlib';encoded.write_bytes(data)
        run([out/'reference','check',encoded,raw],out,f'zlib-check-{task:02d}')
        tokens=inspect(bytes(data),raw.read_bytes(),bool(task&1))
        if task&1:seen_lengths.update(tokens['lengths'])
        records.append({'task':task,'input_bytes':len(raw.read_bytes()),'output_bytes':len(data),**tokens})
    assert set(range(3,259))<=seen_lengths,'必须观测全部长度3..258的实际匹配token'
    assert len(records[14]['blocks'])==2,'STORED32769必须跨块'
    raw=(vectors/'raw-00.bin').read_bytes()
    wrong=out/'wrong-answer.bin';wrong.write_bytes(bytes([raw[0]^1])+raw[1:])
    run([out/'reference','check',out/'encoded-00.zlib',wrong],out,'negative-data',expected=1)
    assert 'REFERENCE_DATA_MISMATCH' in (out/'negative-data.log').read_text()
    changed=bytearray(actual[0]);changed[-1]^=1
    broken=out/'broken-adler.zlib';broken.write_bytes(changed)
    run([out/'reference','check',broken,vectors/'raw-00.bin'],out,'negative-adler',expected=1)
    assert 'REFERENCE_ZLIB_ERROR' in (out/'negative-adler.log').read_text()
    require_frozen()
    assert head==subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'执行中源码提交发生改变'
    record={'status':0,'head':head,'component':'zlib-stored-fixed','tasks':records,
            'wide':args.wide,'continuous':args.continuous,
            'observed_match_lengths':sorted(seen_lengths),'negative_controls':['wrong-answer','adler'],
            'threads_per_simulation':1,'reference':'zlib1.3.2','complete_png_scope':False,
            'emission_root':str(rtl),'emission_commit':args.emission_commit}
    (out/'completion.json').write_text(json.dumps(record,indent=2)+'\n')
    print(json.dumps({k:v for k,v in record.items() if k not in ('tasks','observed_match_lengths')}))


if __name__=='__main__':main()
