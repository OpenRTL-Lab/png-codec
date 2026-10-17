#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: inflate_boundary_vectors.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""补充实际Inflate的跨字母表码长重复与非法树/保留token，不替代外部参考。"""
from inflate_vectors import Bits
from verify_huffman import canonical

def vectors():
    cases=[]
    lengths=[0]*258;lengths[0]=1;lengths[256]=lengths[257]=2
    b=Bits();used=b.dynamic(lengths,[2]*4,[0,(3,1)])
    assert 16 in used
    cases.append(('repeat16-cross-ll-distance',b.wrap(),bytes(b.raw),0,None))
    ll=canonical([8]*144+[9]*112+[7]*24+[8]*8);dd=canonical([5]*32)
    for token in (286,287):
        b=Bits();b.put(1,1);b.put(1,2);b.symbol(token,ll)
        cases.append((f'reserved-literal-{token}',b.wrap(),b'',3,None))
    for token in (30,31):
        b=Bits();b.put(1,1);b.put(1,2);b.symbol(65,ll);b.raw.append(65);b.symbol(257,ll);b.symbol(token,dd)
        cases.append((f'reserved-distance-{token}',b.wrap(),b'A',3,4))
    order=(16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15)
    def header(lengths,distances):
        b=Bits();b.put(1,1);b.put(2,2);b.put(len(lengths)-257,5);b.put(len(distances)-1,5);b.put(14,4)
        cl=[0]*19;cl[0]=cl[1]=1;table=canonical(cl)
        for symbol in order[:18]:b.put(cl[symbol],3)
        for n in list(lengths)+list(distances):b.symbol(n,table)
        return b
    lengths=[0]*257;lengths[0]=lengths[1]=1
    b=header(lengths,[0]);cases.append(('missing-eob-tree',b.wrap(),b'',3,None))
    lengths[256]=1
    b=header(lengths,[0]);cases.append(('oversubscribed-literal-tree',b.wrap(),b'',3,None))
    for kind in ('first-repeat16','repeat18-overrun'):
        b=Bits();b.put(1,1);b.put(2,2);b.put(0,5);b.put(0,5);b.put(0,4)
        # HCLEN4只包含16/17/18/0，完整树0:1、16:2、18:2。
        cl=[0]*19;cl[0]=1;cl[16]=cl[18]=2;table=canonical(cl)
        for symbol in order[:4]:b.put(cl[symbol],3)
        if kind=='first-repeat16':b.symbol(16,table);b.put(0,2)
        else:
            for _ in range(2):b.symbol(18,table);b.put(127,7)
        cases.append((kind,b.wrap(),b'',3,None))
    return cases
