#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: verify_sample.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""首个完整Gray8/STORED样本：C独立参考→Chisel验证环境→真实SV→libpng核对。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def require_frozen():
    from external_qualification import lock
    lock()  # Also verify the pinned independent source/list/bind inputs, if enabled.
    # 逐文件校验Git blob，避免NFS两端stat/index缓存导致假阳性或漏检。
    tree=subprocess.check_output(['git','ls-tree','-rz','HEAD'],cwd=ROOT)
    names=[]
    for entry in tree.split(b'\0'):
        if not entry:
            continue
        header,name=entry.split(b'\t',1)
        mode,kind,digest=header.split()
        if kind!=b'blob':
            raise RuntimeError('冻结源码不允许子模块')
        path=ROOT/os.fsdecode(name)
        data=path.read_bytes()
        actual=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest().encode()
        if actual!=digest:
            raise RuntimeError('生成前源码须冻结提交：'+os.fsdecode(name))
        names.append(name)
    indexed=subprocess.check_output(['git','ls-files','-z'],cwd=ROOT).split(b'\0')
    if set(names)!=set(n for n in indexed if n):
        raise RuntimeError('暂存区文件集合与HEAD不一致')


def run(cmd, out, label, expected=0):
    from external_qualification import prepare_compile,finish_compile
    cmd,qualification=prepare_compile([str(x) for x in cmd],out)
    with (out/(label+'.log')).open('xb') as f:
        p=subprocess.run([str(x) for x in cmd],cwd=ROOT,stdout=f,stderr=subprocess.STDOUT)
    finish_compile(out,qualification,p.returncode)
    if p.returncode!=expected:
        raise RuntimeError(f'{label}退出{p.returncode}，查看{out/(label+".log")}')


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out',required=True)
    ap.add_argument('--width',type=int,default=17)
    ap.add_argument('--height',type=int,default=9)
    ap.add_argument('--jobs',type=int,default=4)
    ap.add_argument('--negative',action='store_true')
    ap.add_argument('--reuse-emission',help='复用同参数的既有生成目录（包含rtl/vectors）')
    ap.add_argument('--emission-commit',help='既有生成对应的冻结源码提交')
    args=ap.parse_args()
    os.chdir(ROOT)
    out=Path(args.out)
    if out.is_absolute() or '..' in out.parts or out.parts[0]!='build':
        raise ValueError('输出只能是build内的新相对路径')
    out.mkdir(parents=True,exist_ok=False)
    require_frozen()
    prefix=ROOT/'dependencies/install'
    png_library=next((prefix/d/'libpng16.a' for d in ('lib','lib64')
                      if (prefix/d/'libpng16.a').is_file()),None)
    if png_library is None:
        raise RuntimeError('请先构建固定libpng参考静态库')
    run([os.environ.get('CC','cc'),'-O2','-std=c11','-Wall','-Wextra','-Werror',
         '-I'+str(prefix/'include'),ROOT/'reference/png_reference.c',png_library,
         prefix/'lib/libz.a','-lm','-o',out/'reference'],out,'reference-compile')
    run([out/'reference','version'],out,'reference-version')
    vectors=out/'vectors';vectors.mkdir()
    run([out/'reference','generate',vectors,args.width,args.height],out,'reference-generate')
    rtl=out/'rtl'
    if args.reuse_emission:
        if not args.emission_commit or args.negative:
            raise ValueError('复用需声明源码提交且不能改变负控模式')
        previous=Path(args.reuse_emission)
        for name in ('input.hex','golden.hex'):
            assert (previous/'vectors'/name).read_bytes()==(vectors/name).read_bytes()
        assert not subprocess.check_output(['git','diff',args.emission_commit,'HEAD','--','src','build.mill'])
        rtl=previous/'rtl'
    else:
        run(shlex.split(os.environ.get('MILL','mill'))+['-i','png.tool.runMain','openimage.png.EmitSample',
            rtl,vectors,args.width,args.height,int(args.negative),'0'],out,'emit')
    # Chisel7验证层是显式bind模块，不能只glob顶层SV而漏掉断言/打印。
    files=[rtl/p for p in (rtl/'filelist.f').read_text().splitlines() if p.strip()]
    binding=rtl/'verification/assert/layers-PngEncoderSample-Verification-Assert.sv'
    assert binding.is_file() and any('Verification_Assert.sv' in str(p) for p in files)
    (out/'simulation.f').write_text('+incdir+'+str(ROOT/rtl/'verification')+'\n'+
        ''.join(str(ROOT/p)+'\n' for p in files+[binding]))
    product=[rtl/n for n in ['PngStoredEncoder.sv','BytePacker.sv']]
    assert all(p.exists() for p in product)
    assert all('$readmem' not in p.read_text() for p in product)
    (out/'product.f').write_text(''.join(str(ROOT/p)+'\n' for p in product))
    run(['verilator','--lint-only','--assert','--top-module','PngStoredEncoder','-f',out/'product.f'],out,'product-lint')
    run(['verilator','--cc','--exe','--build','--assert','--top-module','PngEncoderSample',
         '--Mdir',out/'obj','-j',args.jobs,'-CFLAGS','-O2 -std=c++17',
         '-f',out/'simulation.f',ROOT/'sim/clock_main.cpp'],out,'verilator-build')
    log=out/'simulation.log'
    with log.open('xb') as f:
        p=subprocess.run([str(out/'obj/VPngEncoderSample')],cwd=ROOT,stdout=f,stderr=subprocess.STDOUT)
    text=log.read_text(errors='replace')
    if args.negative:
        assert p.returncode!=0 and 'PNG_BYTE_MISMATCH' in text,'负控不是由像素比较器拒绝'
    else:
        assert p.returncode==0 and 'PNG_ENCODER_SAMPLE_PASS' in text,'真实RTL样本未通过'
        actual=bytearray()
        for data,keep in re.findall(r'PNG_DATA\s+([0-9a-fA-F]+)\s+([0-9a-fA-F]+)',text):
            d=int(data,16);k=int(keep,16)
            actual.extend((d>>(8*i))&255 for i in range(8) if k&(1<<i))
        observed=out/'observed.png';observed.write_bytes(actual)
        assert actual==(vectors/'canonical.png').read_bytes(),'导出字节与实际检查不一致'
        run([out/'reference','check',observed,vectors/'raw.bin',args.width,args.height],out,'rtl-to-libpng')
    record={'status':0,'stage':'Gray8-None-STORED','negative':args.negative,
            'head':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
            'width':args.width,'height':args.height,'pixels':args.width*args.height,
            'reference':{'libpng':'1.6.59','zlib':'1.3.2'},'threads_per_simulation':1,
            'emission_root':str(rtl),'emission_commit':args.emission_commit,
            'complete_png_scope':False}
    (out/'completion.json').write_text(json.dumps(record,indent=2)+'\n')
    print(json.dumps(record))


if __name__=='__main__':
    main()
