#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: verify_inflate.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""固定zlib压缩/离线核对与原创RFC定向码流→真实Chisel Inflate逐字节检查。"""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
from inflate_vectors import Bits,directed
from verify_huffman import canonical
from verify_sample import ROOT,require_frozen,run


def product_closure(rtl,top):
    from external_qualification import product_files
    external=product_files(rtl,top)
    if external is not None:return external
    files=[rtl/p for p in (rtl/'filelist.f').read_text().splitlines() if p.strip()]
    modules={name:p for p in files if p.parent==rtl for name in re.findall(r'^module\s+(\w+)',p.read_text(),re.M)}
    pending=[top];selected=set()
    while pending:
        name=pending.pop()
        if name in selected:continue
        selected.add(name);text=modules[name].read_text()
        pending.extend(n for n in modules if re.search(r'\b'+re.escape(n)+r'\s+\w+\s*\(',text))
    return sorted({modules[n] for n in selected})


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out',required=True);ap.add_argument('--jobs',type=int,default=4)
    ap.add_argument('--negative',action='store_true');ap.add_argument('--limit',type=int)
    ap.add_argument('--fast',action='store_true',help='v1.2双字节位池/快根表/同步历史路径')
    ap.add_argument('--continuous',action='store_true',help='fast路径理想连续输入与接收')
    ap.add_argument('--boundary-only',action='store_true',help='只跑新增树/保留token/HDIST32边界')
    ap.add_argument('--hdist31-only',action='store_true',help='只补HDIST31标准探针，不重跑已有HDIST32')
    ap.add_argument('--lookahead-only',action='store_true',help='匹配边界预解析增量，不替代原26/树边界')
    ap.add_argument('--reuse-emission');ap.add_argument('--emission-commit')
    args=ap.parse_args()
    assert not args.continuous or args.fast
    if args.hdist31_only:args.boundary_only=True
    if args.lookahead_only:
        assert args.fast and not args.boundary_only
        args.boundary_only=True
    os.chdir(ROOT);require_frozen();head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    out=Path(args.out)
    if out.is_absolute() or '..' in out.parts or out.parts[0]!='build':raise ValueError('输出为build新目录')
    out.mkdir(parents=True,exist_ok=False);v=out/'vectors';v.mkdir();prefix=ROOT/'dependencies/install'
    run([os.environ.get('CC','cc'),'-O2','-std=c11','-Wall','-Wextra','-Werror','-I'+str(prefix/'include'),
         ROOT/'reference/zlib_reference.c',prefix/'lib/libz.a','-o',out/'reference'],out,'reference-compile')
    vectors=[];records=[]
    def add(name,z,raw,status=0,declared=None,reference=True):
        number=len(vectors);zf=v/f'{number:02d}.zlib';rf=v/f'{number:02d}.raw';zf.write_bytes(z);rf.write_bytes(raw)
        if reference:
            run([out/'reference','check',zf,rf],out,f'reference-check-{number:02d}',expected=0 if status==0 else 1)
        vectors.append((z,raw,status,len(raw) if declared is None else declared));records.append({'name':name,'status':status,'reference_checked':reference})
    if args.lookahead_only:
        from match_lookahead_vectors import vectors as lookahead_vectors
        for name,z,raw,status,declared in lookahead_vectors():add(name,z,raw,status,declared)
    elif args.boundary_only:
        from inflate_boundary_vectors import vectors as boundary_vectors
        for name,z,raw,status,declared in ([] if args.hdist31_only else boundary_vectors()):add(name,z,raw,status,declared)
        lengths=[0]*257;lengths[0]=lengths[256]=1
        distance_count=31 if args.hdist31_only else 32
        b=Bits();b.dynamic(lengths,[0]*distance_count,[0],rle=False)
        add(f'hdist{distance_count}-unused-reserved-codes',b.wrap(),bytes(b.raw),reference=False)
        if args.hdist31_only:
            run([out/'reference','check',v/'00.zlib',v/'00.raw'],out,'reference-hdist31',expected=1)
            assert 'REFERENCE_ZLIB_ERROR' in (out/'reference-hdist31.log').read_text()
    for name,z,raw in ([] if args.boundary_only else directed()):add(name,z,raw)
    patterns=[bytes(range(256))*260,b'OpenImage PNG Inflate '+bytes(range(53))]*1
    patterns[1]=patterns[1]*600
    patterns.append((b'OpenImage original canonical Huffman and overlapping history. '*1200)+b'A'*6000)
    for mode in ([] if args.boundary_only else range(3)):
        raw=patterns[mode];source=v/f'software-{mode}.raw';compressed=v/f'software-{mode}.zlib';source.write_bytes(raw)
        run([out/'reference','compress',source,compressed,mode],out,f'reference-compress-{mode}')
        z=compressed.read_bytes()
        if mode==2:assert (z[2]>>1)&3==2,'必须真实测试软件动态块'
        add(f'zlib1.3.2-mode{mode}',z,raw)
    if not args.boundary_only:
        base=directed()[2];good=bytearray(base[1]);raw=base[2]
        bad=good.copy();bad[1]^=1;add('header-check',bytes(bad),raw,3)
        bad=good.copy();bad[-1]^=1;add('adler',bytes(bad),raw,4)
        add('truncated-adler',bytes(good[:-1]),raw,3)
        add('truncated-deflate',bytes(good[:4]),raw,3)
        add('expected-output-short',bytes(good),raw,3,len(raw)-1,False)
        add('expected-output-long',bytes(good),raw,3,len(raw)+1,False)
        add('reserved-block',bytes.fromhex('78010700000001'),b'',3)
        b=Bits();b.stored(b'abc',True);bad=bytearray(b.wrap());bad[5]^=1;add('stored-nlen',bytes(bad),b'abc',3)
        # 合法头且FDICT置位，属于明确不支持。
        f=32;f+=(-(0x78*256+f))%31;add('dictionary-unsupported',bytes((0x78,f))+bytes(4),b'',2)
        b=Bits();b.stored(bytes(range(256)));b.put(1,1);b.put(1,2)
        ll=canonical([8]*144+[9]*112+[7]*24+[8]*8);dd=canonical([5]*32)
        b.symbol(257,ll);b.symbol(16,dd);b.put(0,7);b.symbol(256,ll)
        add('distance-before-history',b.wrap(),bytes(b.raw),3)
        b=Bits();b.stored(bytes(range(256))*2);b.fixed([(3,257)])
        add('distance-outside-header-window',b.wrap(8),bytes(b.raw),3,reference=False)
    # 标准字段容量与固定参考的已知边界探针，仅记录分歧，不冒称库接受。
    lengths=[0]*257;lengths[0]=lengths[256]=1
    b=Bits();b.dynamic(lengths,[0]*32,[0],rle=False)
    (v/'hdist32.zlib').write_bytes(b.wrap());(v/'hdist32.raw').write_bytes(b.raw)
    run([out/'reference','check',v/'hdist32.zlib',v/'hdist32.raw'],out,'reference-hdist32',expected=1)
    assert 'REFERENCE_ZLIB_ERROR' in (out/'reference-hdist32.log').read_text()
    if args.limit:vectors=vectors[:args.limit];records=records[:args.limit]
    encoded=bytearray();raw=bytearray();cases=[]
    for z,r,status,declared in vectors:
        cases.append([len(z),len(encoded),len(r),len(raw),status,declared]);encoded.extend(z);raw.extend(r)
    for name,data in [('encoded.hex',encoded),('raw.hex',raw)]:
        # 参考ROM最少两地址，避开firtool单地址存储降级；有效答案长度仍取cases。
        payload=bytes(data).ljust(2,b'\x00') if name=='raw.hex' else bytes(data)
        (v/name).write_text(''.join(f'{b:02x}\n' for b in payload) or '00\n')
    (v/'cases.txt').write_text(''.join(' '.join(map(str,c))+'\n' for c in cases))
    rtl=out/'rtl'
    if args.reuse_emission:
        assert args.emission_commit
        previous=Path(args.reuse_emission)
        for name in ('encoded.hex','raw.hex','cases.txt'):assert (previous/'vectors'/name).read_bytes()==(v/name).read_bytes()
        assert not subprocess.check_output(['git','diff',args.emission_commit,'HEAD','--','src','build.mill'])
        rtl=previous/'rtl'
    else:
        extra=['fast','continuous' if args.continuous else 'random'] if args.fast else []
        run(shlex.split(os.environ.get('MILL','mill'))+['-i','png.tool.runMain','openimage.png.EmitInflate',rtl,v,'negative' if args.negative else 'normal',*extra],out,'emit')
    # 不让不同生成模型同名PCH/构造器命中环境的跨目录编译缓存。
    os.environ['CCACHE_DISABLE']='1'
    files=[rtl/p for p in (rtl/'filelist.f').read_text().splitlines() if p.strip()]
    binds=sorted((rtl/'verification/assert').glob('layers-*-Verification-Assert.sv'));assert binds
    (out/'simulation.f').write_text('+define+ENABLE_INITIAL_MEM_\n+incdir+'+str(ROOT/rtl/'verification')+'\n+incdir+'+str(ROOT/rtl/'verification/assert')+'\n'+''.join(str(ROOT/p)+'\n' for p in files+binds))
    top='FastInflate' if args.fast else 'Inflate'
    product=product_closure(rtl,top)
    for p in product:assert '$readmem' not in p.read_text()
    run(['verilator','--lint-only','--assert','--top-module',top,*product],out,'product-lint')
    run(['verilator','--cc','--exe','--build','--assert','--top-module','InflateSuite','--Mdir',out/'obj','-j',args.jobs,
         '-CFLAGS','-O2 -std=c++17','-f',out/'simulation.f',ROOT/'sim/inflate_clock_main.cpp'],out,'verilator-build')
    p=subprocess.run([out/'obj/VInflateSuite'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    log=p.stdout.decode('utf-8',errors='replace');(out/'simulation.log').write_text(log)
    (out/'simulation-exit.json').write_text(json.dumps({'returncode':p.returncode,'log_bytes':len(p.stdout)})+'\n')
    if args.negative:assert p.returncode!=0 and 'INFLATE_BYTE_MISMATCH' in log
    else:assert p.returncode==0 and 'INFLATE_SUITE_PASS' in log and log.count('INFLATE_CASE_PASS')==len(cases)
    require_frozen();assert head==subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    record={'status':0,'head':head,'cases':records,'negative':args.negative,'threads_per_simulation':1,
            'boundary_only':args.boundary_only,'hdist31_only':args.hdist31_only,
            'lookahead_only':args.lookahead_only,
            'fast':args.fast,'continuous':args.continuous,
            'reference':'zlib1.3.2','hdist32_reference_rejected':True,'complete_png_decode':False,
            'emission_commit':args.emission_commit,'emission_root':str(rtl)}
    (out/'completion.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps(record))
if __name__=='__main__':main()
