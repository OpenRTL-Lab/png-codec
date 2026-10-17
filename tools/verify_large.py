#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: verify_large.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""完整尺寸真实PNG编/解码，原图按坐标在线生成与核对，无全图参考副本。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
from verify_inflate import product_closure
from verify_sample import ROOT,require_frozen,run

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',required=True)
    ap.add_argument('--direction',choices=['encode','decode'],required=True);ap.add_argument('--width',type=int,default=8192);ap.add_argument('--height',type=int,default=8192)
    ap.add_argument('--jobs',type=int,default=2);ap.add_argument('--negative',action='store_true');args=ap.parse_args()
    assert 1<=args.width<=8192 and 1<=args.height<=8192
    os.chdir(ROOT);require_frozen();head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip();out=Path(args.out)
    if out.is_absolute() or '..' in out.parts or out.parts[0]!='build':raise ValueError('输出为build新目录')
    out.mkdir(parents=True,exist_ok=False);prefix=ROOT/'dependencies/install'
    pnglib=next(p for p in (prefix/'lib64/libpng16.a',prefix/'lib/libpng16.a') if p.is_file())
    run([os.environ.get('CC','cc'),'-O2','-std=c11','-Wall','-Wextra','-Werror','-I'+str(prefix/'include'),ROOT/'reference/large_reference.c',pnglib,prefix/'lib/libz.a','-lm','-o',out/'reference'],out,'reference-compile')
    png=out/'software.png';actual=out/'observed.png'
    run([out/'reference','generate',png,args.width,args.height],out,'reference-generate')
    run([out/'reference','check',png,args.width,args.height],out,'reference-check')
    rtl=out/'rtl';run(shlex.split(os.environ.get('MILL','mill'))+['-i','png.tool.runMain','openimage.png.EmitLarge',rtl,args.direction,args.width,args.height,png,actual,'negative' if args.negative else 'normal'],out,'emit')
    files=[rtl/p for p in (rtl/'filelist.f').read_text().splitlines() if p.strip()];binds=sorted((rtl/'verification/assert').glob('layers-*-Verification-Assert.sv'));assert binds
    (out/'simulation.f').write_text('+define+ENABLE_INITIAL_MEM_\n+incdir+'+str(ROOT/rtl/'verification')+'\n+incdir+'+str(ROOT/rtl/'verification/assert')+'\n'+''.join(str(ROOT/p)+'\n' for p in files+binds))
    name='PngEncoder' if args.direction=='encode' else 'PngDecoder';product=product_closure(rtl,name)
    assert all('$readmem' not in p.read_text() and 'LargeCapture' not in p.read_text() for p in product)
    run(['verilator','--lint-only','--assert','--top-module',name,*product],out,'product-lint')
    os.environ['CCACHE_DISABLE']='1'
    run(['verilator','--cc','--exe','--build','--assert','--top-module','LargeSuite','--Mdir',out/'obj','-j',args.jobs,'-CFLAGS','-O2 -std=c++17','-f',out/'simulation.f',ROOT/'sim/large_clock_main.cpp'],out,'verilator-build')
    p=subprocess.run([out/'obj/VLargeSuite'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT);log=p.stdout.decode('utf-8',errors='replace')
    (out/'simulation.log').write_text(log);(out/'simulation-exit.json').write_text(json.dumps({'returncode':p.returncode,'log_bytes':len(p.stdout)})+'\n')
    if args.direction=='decode' and args.negative:assert p.returncode!=0 and 'PNG_LARGE_BYTE_MISMATCH' in log
    else:
        marker='PNG_LARGE_ENCODER_PASS' if args.direction=='encode' else 'PNG_LARGE_DECODER_PASS'
        assert p.returncode==0 and marker in log
        if args.direction=='encode':
            q=subprocess.run([str(x) for x in [out/'reference','check',actual,args.width,args.height]],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
            text=q.stdout.decode('utf-8',errors='replace');(out/'rtl-to-libpng.log').write_text(text)
            if args.negative:assert q.returncode!=0 and 'pixel mismatch' in text
            else:assert q.returncode==0 and 'LARGE_REFERENCE_PASS' in text
    require_frozen();assert head==subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    result={'status':0,'head':head,'direction':args.direction,'width':args.width,'height':args.height,'pixels':args.width*args.height,
            'raw_bytes':((args.width+7)//8)*args.height,'format':[0,1],'encoder_filter':0,'encoder_mode':'FIXED','negative':args.negative,
            'threads_per_simulation':1,'reference_row_bytes':(args.width+7)//8,'verification_capture_limit':1048576 if args.direction=='encode' else 0,
            'software_png_sha256':hashlib.sha256(png.read_bytes()).hexdigest()}
    if actual.exists():result['observed_png_sha256']=hashlib.sha256(actual.read_bytes()).hexdigest()
    (out/'completion.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
if __name__=='__main__':main()
