#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: verify_streams.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""直接Chisel检查共享适配器：1B/c无气泡、背压、全部末拍及指定负控。"""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
from verify_sample import ROOT, require_frozen, run

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out',required=True)
    ap.add_argument('--negative',action='store_true')
    ap.add_argument('--jobs',type=int,default=2)
    args=ap.parse_args();os.chdir(ROOT);require_frozen()
    head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    out=Path(args.out)
    assert not out.is_absolute() and out.parts[0]=='build' and '..' not in out.parts
    out.mkdir(exist_ok=False);rtl=out/'rtl'
    run(shlex.split(os.environ.get('MILL','mill'))+['-i','png.tool.runMain','openimage.png.EmitStreams',rtl,
         'negative' if args.negative else 'normal'],out,'emit')
    files=[rtl/p for p in (rtl/'filelist.f').read_text().splitlines() if p.strip()]
    bindings=sorted((rtl/'verification/assert').glob('layers-*-Verification-Assert.sv'))
    assert bindings
    (out/'simulation.f').write_text('+incdir+'+str(ROOT/rtl/'verification')+'\n'+
        '+incdir+'+str(ROOT/rtl/'verification/assert')+'\n'+''.join(str(ROOT/p)+'\n' for p in files+bindings))
    run(['verilator','--cc','--exe','--build','--assert','--top-module','StreamAdapterSuite',
         '--Mdir',out/'obj','-j',args.jobs,'-CFLAGS','-O2 -std=c++17','-f',out/'simulation.f',
         ROOT/'sim/stream_clock_main.cpp'],out,'verilator-build')
    with (out/'simulation.log').open('xb') as log:
        p=subprocess.run([str(out/'obj/VStreamAdapterSuite')],stdout=log,stderr=subprocess.STDOUT)
    text=(out/'simulation.log').read_text(errors='replace')
    if args.negative: assert p.returncode!=0 and 'STREAM_BYTE_MISMATCH' in text
    else: assert p.returncode==0 and 'STREAM_SUITE_PASS' in text and text.count('STREAM_CASE_PASS')==58
    record={'status':0,'head':head,'negative':args.negative,'tasks':58 if not args.negative else 1,
            'threads_per_simulation':1,'simulation_returncode':p.returncode}
    (out/'completion.json').write_text(json.dumps(record,indent=2)+'\n')
    print(json.dumps(record))
if __name__=='__main__':main()
