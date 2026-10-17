#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: performance_vectors.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""性能代表图案、PNG封装与完整DEFLATE类型解析；只用于离线验证。"""
import struct
import zlib
from functools import lru_cache
from inspect_deflate import BASES,EXTRAS,DIST_BASE,DIST_EXTRA
from inflate_vectors import Bits
from verify_huffman import canonical

SEED=0x13579bdf
CHANNELS={0:1,2:3,3:1,4:2,6:4}

def sample(x,y,c,w,h,depth,pattern):
    mask=(1<<depth)-1
    if pattern=='constant':return mask//3
    if pattern=='gradient':return (3*x+5*y+17*c+(mask//2 if (x>=w//2) ^ (y>=h//2) else 0))&mask
    assert pattern=='noise'
    v=((y*w+x)*CHANNELS[0]+c)^SEED
    # 调用方噪声仅Gray8；冻结32位无状态整数混合，不依赖Python随机版本。
    v=((v^(v>>16))*0x7feb352d)&0xffffffff
    v=((v^(v>>15))*0x846ca68b)&0xffffffff
    return (v^(v>>16))&mask

@lru_cache(maxsize=32)
def raw_image(w,h,t,depth,pattern):
    out=bytearray();channels=CHANNELS[t]
    for y in range(h):
        row=bytearray((w*channels*depth+7)//8)
        for x in range(w):
            for c in range(channels):
                value=sample(x,y,c,w,h,depth,pattern);q=x*channels+c
                if depth<8:row[q*depth//8]|=value<<(8-depth-q*depth%8)
                elif depth==8:row[q]=value
                else:row[2*q:2*q+2]=value.to_bytes(2,'big')
        out+=row
    return bytes(out)

def metadata(t,depth=8):
    if t!=3:return b'',0,0
    assert depth in (1,2,4,8)
    palette=bytes(v for i in range(1<<depth) for v in (i,255-i,37*i&255))
    alpha=bytes(29*i&255 for i in range((1<<depth)-1))
    return palette+alpha,len(palette),len(alpha)

def paeth(a,b,c):
    p=a+b-c;da,db,dc=abs(p-a),abs(p-b),abs(p-c)
    return a if da<=db and da<=dc else b if db<=dc else c

@lru_cache(maxsize=32)
def filtered(raw,w,h,t,depth,kind):
    n=(w*CHANNELS[t]*depth+7)//8;bpp=max(1,(CHANNELS[t]*depth+7)//8);out=bytearray()
    for y in range(h):
        out.append(kind)
        for x in range(n):
            a=raw[y*n+x-bpp] if x>=bpp else 0;b=raw[(y-1)*n+x] if y else 0
            c=raw[(y-1)*n+x-bpp] if y and x>=bpp else 0
            assert kind in range(5)
            predictor=(0,a,b,(a+b)//2,paeth(a,b,c))[kind]
            out.append((raw[y*n+x]-predictor)&255)
    return bytes(out)

def chunk(name,data):return struct.pack('>I',len(data))+name+data+struct.pack('>I',zlib.crc32(name+data))

def png_file(w,h,t,depth,meta,plen,tlen,compressed):
    out=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,depth,t,0,0,0))
    if plen:out+=chunk(b'PLTE',meta[:plen])
    if tlen:out+=chunk(b'tRNS',meta[plen:plen+tlen])
    return out+chunk(b'IDAT',compressed)+chunk(b'IEND',b'')

def parse_deflate(data,expected):
    """支持三种块和动态树，完整展开并核对Adler/尾部，返回真实块类型。"""
    assert len(data)>=6 and data[0]&15==8 and int.from_bytes(data[:2],'big')%31==0 and not data[1]&32
    body=data[2:-4];pos=0;out=bytearray();kinds=[];matches=0
    def take(n):
        nonlocal pos
        assert pos+n<=len(body)*8,'截断DEFLATE'
        v=sum(((body[(pos+i)//8]>>((pos+i)%8))&1)<<i for i in range(n));pos+=n;return v
    def table(lens):return {tuple(bits):s for s,bits in canonical(lens).items()}
    def symbol(codes):
        bits=[]
        for _ in range(15):
            bits.append(take(1))
            if tuple(bits) in codes:return codes[tuple(bits)]
        raise ValueError('非法规范码')
    while True:
        final=take(1);kind=take(2);kinds.append(kind);assert kind<3
        if kind==0:
            pos=(pos+7)//8*8;n=take(16);assert n^take(16)==65535
            out.extend(take(8) for _ in range(n))
        else:
            if kind==1:ll=[8]*144+[9]*112+[7]*24+[8]*8;dd=[5]*32
            else:
                nl=take(5)+257;nd=take(5)+1;nc=take(4)+4;cl=[0]*19
                for s in (16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15)[:nc]:cl[s]=take(3)
                codes=table(cl);lengths=[]
                while len(lengths)<nl+nd:
                    s=symbol(codes)
                    if s<16:lengths.append(s)
                    elif s==16:
                        assert lengths;lengths.extend([lengths[-1]]*(take(2)+3))
                    else:lengths.extend([0]*(take(3)+3 if s==17 else take(7)+11))
                assert len(lengths)==nl+nd
                ll,dd=lengths[:nl],lengths[nl:]
            lt,dt=table(ll),table(dd)
            while True:
                s=symbol(lt)
                if s<256:out.append(s)
                elif s==256:break
                else:
                    assert 257<=s<=285
                    n=BASES[s-257]+take(EXTRAS[s-257]);d=symbol(dt);assert d<30
                    distance=DIST_BASE[d]+take(DIST_EXTRA[d]);assert distance<=len(out)
                    for _ in range(n):out.append(out[-distance])
                    matches+=1
        if final:break
    assert (pos+7)//8==len(body) and bytes(out)==expected
    assert int.from_bytes(data[-4:],'big')==zlib.adler32(expected)
    return {'block_types':kinds,'matches':matches}

def forced_stream(raw,kind):
    """库自动换类型时的显式RFC回退；不冒称其压缩选择来自固定库。"""
    b=Bits()
    if kind==0:
        for off in range(0,len(raw),65535):b.stored(raw[off:off+65535],off+65535>=len(raw))
    elif kind==1:b.fixed(raw)
    else:
        assert kind==2
        b.put(1,1);b.put(2,2);b.put(0,5);b.put(0,5);b.put(15,4)
        # 0..15码长符号均4位完整树；literal 0..254为8位、255/EOB为9位。
        cl=[4]*16+[0]*3
        for s in (16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15):b.put(cl[s],3)
        ll=[8]*255+[9,9];dd=[1];codes=canonical(cl)
        for n in ll+dd:b.symbol(n,codes)
        b.tokens(raw,canonical(ll),canonical(dd))
    assert bytes(b.raw)==raw
    return b.wrap()
