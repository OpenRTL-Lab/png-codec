#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: inspect_deflate.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""原创RFC1951 STORED/FIXED解析器：独立于产品匹配选择，检查真实token。"""
import struct
import zlib

BASES=(3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258)
EXTRAS=(0,)*8+(1,)*4+(2,)*4+(3,)*4+(4,)*4+(5,)*4+(0,)
DIST_BASE=(1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577)
DIST_EXTRA=(0,0,0,0)+tuple(v for x in range(1,14) for v in (x,x))


def inspect(data,expected,fixed):
    assert len(data)>=6 and data[0]==0x78 and data[1]==1
    payload=data[2:-4];bit=0;output=bytearray();matches=[];blocks=[]
    def take(n):
        nonlocal bit
        if bit+n>8*len(payload):raise ValueError('截断DEFLATE')
        v=sum(((payload[(bit+i)//8]>>((bit+i)%8))&1)<<i for i in range(n));bit+=n;return v
    table={}
    for symbol in range(288):
        if symbol<144:code,n=symbol+48,8
        elif symbol<256:code,n=symbol-144+400,9
        elif symbol<280:code,n=symbol-256,7
        else:code,n=symbol-280+192,8
        table[(code,n)]=symbol
    def symbol():
        code=0
        for n in range(1,10):
            code=(code<<1)|take(1)
            if (code,n) in table:return table[(code,n)]
        raise ValueError('非法固定码')
    while True:
        final=take(1);kind=take(2);blocks.append(kind)
        assert kind==(1 if fixed else 0),'未遵守编码块模式'
        if kind==0:
            bit=(bit+7)//8*8
            n=take(16);neg=take(16);assert n^neg==65535
            output.extend(take(8) for _ in range(n))
        else:
            while True:
                s=symbol()
                if s<256:output.append(s)
                elif s==256:break
                else:
                    assert 257<=s<=285
                    n=BASES[s-257]+take(EXTRAS[s-257])
                    # 距离固定码是MSB先发，不能按extra位的LSB规则直接解读。
                    dc=0
                    for _ in range(5):dc=(dc<<1)|take(1)
                    assert dc<30
                    d=DIST_BASE[dc]+take(DIST_EXTRA[dc]);assert 1<=d<=len(output)
                    matches.append((n,d))
                    for _ in range(n):output.append(output[-d])
        if final:break
    while bit%8:assert take(1)==0
    assert bit==len(payload)*8,'块结束后有多余压缩字节'
    assert bytes(output)==expected,'独立token展开不匹配'
    assert struct.unpack('>I',data[-4:])[0]==zlib.adler32(expected)
    return {'blocks':blocks,'matches':len(matches),'lengths':sorted(set(n for n,d in matches)),
            'distances':sorted(set(d for n,d in matches))}
