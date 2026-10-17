#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: verify_decoder_edges.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""完整PNG边界文件、错误/容量/恢复和指定比较器负控。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess
import zlib
from verify_inflate import product_closure
from verify_sample import ROOT,require_frozen,run

SIGNATURE=b'\x89PNG\r\n\x1a\n'

def chunk(t,p):return struct.pack('>I',len(p))+t+p+struct.pack('>I',zlib.crc32(t+p))
def pack(parts):return SIGNATURE+b''.join(chunk(t,p) for t,p in parts)
def unpack(data):
    assert data[:8]==SIGNATURE;pos=8;parts=[]
    while pos<len(data):
        n=int.from_bytes(data[pos:pos+4],'big');t=data[pos+4:pos+8];p=data[pos+8:pos+8+n]
        assert len(p)==n and zlib.crc32(t+p)==int.from_bytes(data[pos+8+n:pos+12+n],'big')
        parts.append((t,p));pos+=n+12
    assert pos==len(data);return parts
def replace_idat(parts,z,split=None):
    before=[(t,p) for t,p in parts if t not in (b'IDAT',b'IEND')]
    pieces=split if split is not None else [z]
    return before+[(b'IDAT',p) for p in pieces]+[(b'IEND',b'')]
def compressed(parts):return b''.join(p for t,p in parts if t==b'IDAT')
def filtered(raw,w,h,t,b,mixed=True):
    c={0:1,2:3,3:1,4:2,6:4}[t];r=(w*c*b+7)//8;bpp=(c*b+7)//8;result=bytearray();prev=bytes(r)
    for y in range(h):
        row=raw[y*r:(y+1)*r];f=y%5 if mixed else 0;result.append(f)
        for x,value in enumerate(row):
            a=row[x-bpp] if x>=bpp else 0;u=prev[x];ul=prev[x-bpp] if x>=bpp else 0
            p=a+u-ul;pa,pb,pc=abs(p-a),abs(p-u),abs(p-ul)
            predictor=(0,a,u,(a+u)//2,a if pa<=pb and pa<=pc else u if pb<=pc else ul)[f]
            result.append((value-predictor)&255)
        prev=row
    return bytes(result)

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',required=True);ap.add_argument('--jobs',type=int,default=4)
    ap.add_argument('--source',default='build/decoder-matrix-r1');ap.add_argument('--negative',choices=['pixel','meta','tag','status'])
    ap.add_argument('--reuse-emission');ap.add_argument('--emission-commit');args=ap.parse_args()
    os.chdir(ROOT);require_frozen();head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    out=Path(args.out)
    if out.is_absolute() or '..' in out.parts or out.parts[0]!='build':raise ValueError('输出为build新目录')
    out.mkdir(parents=True,exist_ok=False);v=out/'vectors';v.mkdir();source=Path(args.source)
    baseline=json.loads((source/'completion.json').read_text());assert baseline['status']==0
    png_all=bytearray();raw_all=bytearray();meta_all=bytearray();cases=[];records=[]
    seeds={}
    for index in (0,45,75,105,150,195):
        d=source/'vectors'/f'case-{index:03d}';cfg=baseline['tasks'][index]
        seeds[index]=((d/'software.png').read_bytes(),(d/'raw.bin').read_bytes(),(d/'meta.bin').read_bytes(),cfg)
    base,raw,meta,cfg=seeds[45];parts=unpack(base)
    def add(label,data=base,gold=raw,metadata=meta,config=cfg,status=0,limit=None,declared=None,mode=0,abort=0,prefix=True,software=False,plen_override=None):
        t,b=config['format'];w,h=config['width'],config['height']
        original=unpack(data) if software else None
        plen=next((len(p) for n,p in (original or parts) if n==b'PLTE'),0)
        tlen=len(metadata)-plen
        # 种子及合法变体的格式决定两项元数据长度；错误不会要求尚未公开的描述。
        if original is None:
            plen=(3*(1<<b) if t==3 else 6 if t in (2,6) else 0)
            tlen=len(metadata)-plen
        if plen_override is not None:plen=plen_override;tlen=len(metadata)-plen
        d=v/f'case-{len(cases):03d}';d.mkdir();(d/'file.png').write_bytes(data);(d/'raw.bin').write_bytes(gold);(d/'meta.bin').write_bytes(metadata)
        if software:
            run([source/'checker',d/'file.png',d/'raw.bin',d/'meta.bin',w,h,t,b,plen,tlen],out,f'reference-{len(cases):03d}')
        cases.append([w,h,t,b,len(data),len(png_all),len(gold),len(raw_all),plen,tlen,len(meta_all),status,
                      limit if limit is not None else 1<<30,len(data) if declared is None else declared,mode,abort,int(prefix)])
        records.append({'name':label,'status':status,'abort':abort,'fixed_libpng_checked':software,'input_sha256':hashlib.sha256(data).hexdigest()})
        png_all.extend(data);raw_all.extend(gold);meta_all.extend(metadata)
    add('exact-output-capacity',limit=len(raw),software=True)
    z=compressed(parts)
    add('one-byte-and-zero-idat',pack(replace_idat(parts,z,[b'']+[q for b in z for q in (bytes([b]),b'')])),software=True)
    add('adler-in-four-idats',pack(replace_idat(parts,z,[z[:-4]]+[z[i:i+1] for i in range(len(z)-4,len(z))])),software=True)
    add('trailing-idat-bytes',pack(replace_idat(parts,z+b'not-a-second-stream')),software=True)
    add('ancillary-before-and-after',pack(parts[:1]+[(b'vpAg',b'custom\0data')]+parts[1:-1]+[(b'tEXt',b'key\0value')]+parts[-1:]),software=True)
    for index,(image,gold,metadata,config) in seeds.items():
        p=unpack(image);t,b=config['format'];f=filtered(gold,config['width'],config['height'],t,b)
        add(f'mixed-five-filters-{t}-{b}',pack(replace_idat(p,zlib.compress(f))),gold,metadata,config,software=True)
    # 非零行尾无效位参与下一行预测，但对外必须清零，避免提前归一化破坏Up/Paeth。
    image,gold,metadata,config=seeds[0];p=unpack(image);w,h=config['width'],config['height'];r=(w+7)//8
    original=bytearray(gold)
    for y in range(h):original[(y+1)*r-1]|=0x7f
    tail_image=pack(replace_idat(p,zlib.compress(filtered(original,w,h,0,1))))
    add('nonzero-unused-tail-mixed',tail_image,gold,metadata,config)
    # 标准要求解码忽略透明键未使用高位；与参考库容忍边界分开登记，不伪称库通过。
    p=unpack(base);changed=[(n,b'\xaa'+q[1:]) if n==b'tRNS' else (n,q) for n,q in p]
    add('transparent-unused-high-bits',pack(changed))
    add('capacity-minus-one',limit=len(raw)-1,status=6)
    add('capacity-partial-beat',limit=1,status=6)
    add('invalid-command-zero-input',declared=0,status=1)
    add('invalid-command-zero-capacity',limit=0,status=1)
    add('invalid-command-large-input',declared=(1<<30)+1,status=1)
    add('zero-keep',status=5,mode=1)
    add('non-contiguous-keep',status=5,mode=2)
    add('early-last',status=5,mode=3)
    add('missing-last',status=5,mode=4)
    add('short-non-final',status=5,mode=5)
    # 首拍直接跨声明边界，避免解析器先发现文件尾长度不可能而合法锁存MALFORMED。
    add('over-declared-boundary',declared=7,status=5)
    add('bad-signature',bytes([base[0]^1])+base[1:],status=3)
    bad=bytearray(base);bad[29]^=1;add('ihdr-crc',bytes(bad),status=4)
    bad=bytearray(base);bad[-1]^=1;add('iend-crc',bytes(bad),status=4)
    add('ancillary-crc',base[:33]+chunk(b'vpAg',b'data')[:-1]+bytes([chunk(b'vpAg',b'data')[-1]^1])+base[33:],status=4)
    def ihdr(label,offset,value,status=3):
        p=list(parts);q=bytearray(p[0][1]);q[offset]=value;p[0]=(b'IHDR',bytes(q));add(label,pack(p),status=status)
    ihdr('adam7',12,1,2);ihdr('non-base-filter-method',11,1,2);ihdr('non-base-compression',10,1,2)
    ihdr('invalid-color',9,1);ihdr('invalid-depth',8,3)
    p=list(parts);q=bytearray(p[0][1]);q[:4]=bytes(4);p[0]=(b'IHDR',bytes(q));add('zero-width',pack(p),status=3)
    p=list(parts);q=bytearray(p[0][1]);q[:4]=(8193).to_bytes(4,'big');p[0]=(b'IHDR',bytes(q));add('width-outside-profile',pack(p),status=2)
    add('duplicate-ihdr',pack([parts[0]]+parts),status=3)
    add('missing-ihdr',pack(parts[1:]),status=3)
    add('unknown-critical',pack(parts[:1]+[(b'ABCD',b'')]+parts[1:]),status=2)
    for n in (b'acTL',b'fcTL',b'fdAT'):add('apng-'+n.decode(),pack(parts[:1]+[(n,b'')]+parts[1:]),status=2)
    add('nonletter-chunk',pack(parts[:1]+[(b'vp1g',b'')]+parts[1:]),status=3)
    add('reserved-chunk-bit',pack(parts[:1]+[(b'vpag',b'')]+parts[1:]),status=3)
    add('noncontinuous-idat',pack(replace_idat(parts,z,[z[:2]])[:-1]+[(b'vpAg',b''),(b'IDAT',z[2:]),(b'IEND',b'')]),status=3)
    add('missing-idat',pack([p for p in parts if p[0]!=b'IDAT']),status=3)
    add('extra-after-iend',base+b'x',status=3)
    add('nonempty-iend',pack(parts[:-1]+[(b'IEND',b'x')]),status=3)
    add('truncated-file',base[:-1],status=3)
    badz=bytearray(z);badz[-1]^=1;add('adler',pack(replace_idat(parts,bytes(badz))),status=4)
    add('truncated-zlib',pack(replace_idat(parts,z[:-1])),status=3)
    f=zlib.decompress(z);add('bad-filter',pack(replace_idat(parts,zlib.compress(b'\x05'+f[1:]))),status=3,prefix=False)
    add('excess-filtered-output',pack(replace_idat(parts,zlib.compress(f+b'X'))),status=3)
    add('missing-filtered-output',pack(replace_idat(parts,zlib.compress(f[:-1]))),status=3)
    add('gray-plte-forbidden',pack(parts[:1]+[(b'PLTE',b'\0'*6)]+parts[1:]),status=3)
    add('duplicate-trns',pack(parts[:2]+[parts[1]]+parts[2:]),status=3)
    add('late-trns',pack([parts[0]]+[p for p in parts if p[0]==b'IDAT']+[parts[1],parts[-1]]),metadata=b'',status=3)
    image,gold,metadata,config=seeds[105];p=unpack(image)
    add('index-missing-plte',pack([q for q in p if q[0]!=b'PLTE']),gold,metadata,config,status=3)
    add('palette-not-triplets',pack([(n,q[:-1]) if n==b'PLTE' else (n,q) for n,q in p]),gold,metadata,config,status=3)
    add('index-out-of-palette',pack([(n,q[:3]) if n==b'PLTE' else (n,q) for n,q in p]),gold,metadata[:3]+metadata[6:],config,status=3,prefix=False,plen_override=3)
    for abort,label in ((1,'pixels'),(2,'metadata'),(3,'descriptor')):
        add('reset-held-'+label,abort=abort)
        add('good-after-reset-'+label,software=True)
    add('good-after-all-errors',software=True)
    for name,data in [('png.hex',png_all),('raw.hex',raw_all),('meta.hex',meta_all)]:
        (v/name).write_text(''.join(f'{b:02x}\n' for b in data) or '00\n')
    (v/'cases.txt').write_text(''.join(' '.join(map(str,c))+'\n' for c in cases))
    rtl=out/'rtl'
    if args.reuse_emission:
        assert args.emission_commit;previous=Path(args.reuse_emission)
        for n in ('png.hex','raw.hex','meta.hex','cases.txt'):assert (previous/'vectors'/n).read_bytes()==(v/n).read_bytes()
        assert not subprocess.check_output(['git','diff',args.emission_commit,'HEAD','--','src','build.mill'])
        rtl=previous/'rtl'
    else:run(shlex.split(os.environ.get('MILL','mill'))+['-i','png.tool.runMain','openimage.png.EmitDecoderEdges',rtl,v,args.negative or 'none'],out,'emit')
    files=[rtl/p for p in (rtl/'filelist.f').read_text().splitlines() if p.strip()];binds=sorted((rtl/'verification/assert').glob('layers-*-Verification-Assert.sv'));assert binds
    (out/'simulation.f').write_text('+define+ENABLE_INITIAL_MEM_\n+incdir+'+str(ROOT/rtl/'verification')+'\n+incdir+'+str(ROOT/rtl/'verification/assert')+'\n'+''.join(str(ROOT/p)+'\n' for p in files+binds))
    product=product_closure(rtl,'PngDecoder');assert all('$readmem' not in p.read_text() for p in product)
    run(['verilator','--lint-only','--assert','--top-module','PngDecoder',*product],out,'product-lint')
    os.environ['CCACHE_DISABLE']='1'
    run(['verilator','--cc','--exe','--build','--assert','--top-module','DecoderEdgeSuite','--Mdir',out/'obj','-j',args.jobs,'-CFLAGS','-O2 -std=c++17','-f',out/'simulation.f',ROOT/'sim/decoder_edge_clock_main.cpp'],out,'verilator-build')
    p=subprocess.run([out/'obj/VDecoderEdgeSuite'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT);log=p.stdout.decode('utf-8',errors='replace')
    (out/'simulation.log').write_text(log);(out/'simulation-exit.json').write_text(json.dumps({'returncode':p.returncode})+'\n')
    if args.negative:
        marker={'pixel':'PNG_EDGE_BYTE_MISMATCH','meta':'PNG_EDGE_METADATA_MISMATCH','tag':'PNG_EDGE_TAG','status':'PNG_EDGE_STATUS'}[args.negative]
        assert p.returncode!=0 and marker in log,'负控须命中指定比较器'
    else:assert p.returncode==0 and 'PNG_EDGE_SUITE_PASS' in log and log.count('PNG_EDGE_CASE_PASS')==len(cases)
    require_frozen();assert head==subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    record={'status':0,'head':head,'cases':records,'negative':args.negative,'threads_per_simulation':1,'emission_root':str(rtl),'emission_commit':args.emission_commit}
    (out/'completion.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps({k:x for k,x in record.items() if k!='cases'}))
if __name__=='__main__':main()
