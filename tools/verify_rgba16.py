#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: verify_rgba16.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""复用真实RTL已逐字节验证的原样本，独立规范展开与固定libpng RGBA16逐像素对照。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
from verify_sample import ROOT,require_frozen,run

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',required=True)
    ap.add_argument('--encoder',action='append',default=[]);ap.add_argument('--decoder',action='append',default=[])
    args=ap.parse_args();os.chdir(ROOT);require_frozen();head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    out=Path(args.out)
    if out.is_absolute() or '..' in out.parts or out.parts[0]!='build':raise ValueError('输出为build新目录')
    out.mkdir(parents=True,exist_ok=False);prefix=ROOT/'dependencies/install'
    pnglib=next(p for p in (prefix/'lib64/libpng16.a',prefix/'lib/libpng16.a') if p.is_file())
    run([os.environ.get('CC','cc'),'-O2','-std=c11','-Wall','-Wextra','-Werror',*shlex.split(os.environ.get('CFLAGS','')),'-I'+str(prefix/'include'),ROOT/'reference/rgba16_check.c',pnglib,prefix/'lib/libz.a','-lm','-o',out/'checker'],out,'compile')
    sources=[('encoder',p) for p in args.encoder]+[('decoder',p) for p in args.decoder]
    assert sources;records=[];negatives=[]
    for direction,name in sources:
        source=Path(name);record=json.loads((source/'completion.json').read_text());assert record['status']==0 and not record.get('negative')
        assert 'SUITE_PASS' in (source/'simulation.log').read_text()
        source_cases=[list(map(int,line.split())) for line in (source/'vectors/cases.txt').read_text().splitlines() if line.strip()]
        for i,c in enumerate(record['tasks']):
            case=source/'vectors'/f'case-{i:03d}'
            if direction=='encoder':
                cfg=c['config'];w,h,t,b=cfg[:4];pn,tn=cfg[8:10];image=source/f'encoded-{i:03d}.png'
            else:
                w,h=c['width'],c['height'];t,b=c['format'];pn,tn=source_cases[i][8:10];image=case/'software.png'
            raw,meta=case/'raw.bin',case/'meta.bin'
            cmd=[out/'checker',image,raw,meta,w,h,t,b,pn,tn];label=f'{direction}-{len(records):03d}'
            run(cmd,out,label)
            records.append({'direction':direction,'source':name,'case':i,'width':w,'height':h,'format':[t,b],
                            'png_sha256':hashlib.sha256(image.read_bytes()).hexdigest(),'rtl_run_head':record['head']})
            if not negatives and t==3 and b==8 and pn>=6 and raw.read_bytes()[0]<tn and (raw.read_bytes()[0]^1)<pn//3:
                for which in ('pixel','palette','alpha'):
                    newraw=bytearray(raw.read_bytes());newmeta=bytearray(meta.read_bytes())
                    if which=='pixel':newraw[0]^=1
                    elif which=='palette':newmeta[3*newraw[0]]^=1
                    else:newmeta[pn+newraw[0]]^=1
                    rp=out/f'wrong-{which}.raw';mp=out/f'wrong-{which}.meta';rp.write_bytes(newraw);mp.write_bytes(newmeta)
                    p=subprocess.run([str(x) for x in [out/'checker',image,rp,mp,w,h,t,b,pn,tn]],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
                    log=p.stdout.decode('utf-8',errors='replace');(out/f'negative-{which}.log').write_text(log)
                    assert p.returncode!=0 and 'semantic mismatch' in log
                    negatives.append(which)
    require_frozen();assert head==subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    result={'status':0,'head':head,'cases':records,'negative_controls':negatives,'reference':'fixed libpng1.6.59 official transforms vs independent original-format formulas','bounded_rows':True}
    (out/'completion.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'status':0,'cases':len(records),'negative_controls':negatives}))
if __name__=='__main__':main()
