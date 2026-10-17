#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: inflate_vectors.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""按公开RFC独立构造块/规范码/匹配，输出含准确答案的定向码流。"""
import struct
import zlib
from inspect_deflate import BASES, EXTRAS, DIST_BASE, DIST_EXTRA
from verify_huffman import canonical


class Bits:
    def __init__(self):self.bits=[];self.raw=bytearray()
    def put(self,value,n):self.bits.extend((value>>i)&1 for i in range(n))
    def symbol(self,s,table):self.bits.extend(table[s])
    def align(self):
        while len(self.bits)%8:self.bits.append(0)
    def bytes(self):
        self.align();return bytes(sum(self.bits[i+j]<<j for j in range(8)) for i in range(0,len(self.bits),8))
    def stored(self,data,final=False):
        assert len(data)<=65535
        self.put(int(final),1);self.put(0,2);self.align();self.put(len(data),16);self.put(len(data)^65535,16)
        for b in data:self.put(b,8)
        self.raw.extend(data)
    def tokens(self,tokens,ll,dd):
        for token in tokens:
            if isinstance(token,int):self.symbol(token,ll);self.raw.append(token)
            else:
                length,distance=token
                i=max(i for i,b in enumerate(BASES) if b<=length)
                self.symbol(257+i,ll);self.put(length-BASES[i],EXTRAS[i])
                d=max(i for i,b in enumerate(DIST_BASE) if b<=distance)
                self.symbol(d,dd);self.put(distance-DIST_BASE[d],DIST_EXTRA[d])
                assert distance<=len(self.raw)
                for _ in range(length):self.raw.append(self.raw[-distance])
        self.symbol(256,ll)
    def fixed(self,tokens,final=True):
        self.put(int(final),1);self.put(1,2)
        self.tokens(tokens,canonical([8]*144+[9]*112+[7]*24+[8]*8),canonical([5]*32))
    def dynamic(self,lengths,distances,tokens,final=True,rle=True):
        assert 257<=len(lengths)<=286 and 1<=len(distances)<=32
        self.put(int(final),1);self.put(2,2);self.put(len(lengths)-257,5);self.put(len(distances)-1,5);self.put(14,4)
        order=(16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15)
        cl=[0]*19
        if rle:
            cl[0]=2
            for s in (1,2,3,16,17,18):cl[s]=3
        else:cl[0]=cl[1]=1
        for s in order[:18]:self.put(cl[s],3)
        table=canonical(cl);joined=list(lengths)+list(distances);i=0;used=[]
        while i<len(joined):
            value=joined[i];count=1
            while i+count<len(joined) and joined[i+count]==value:count+=1
            if rle and value==0 and count>=3:
                if count>=11:n=min(count,138);self.symbol(18,table);self.put(n-11,7);used.append(18)
                else:n=min(count,10);self.symbol(17,table);self.put(n-3,3);used.append(17)
            elif rle and i>0 and joined[i-1]==value and count>=3:
                n=min(count,6);self.symbol(16,table);self.put(n-3,2);used.append(16)
            else:n=1;self.symbol(value,table)
            i+=n
        self.tokens(tokens,canonical(lengths),canonical(distances));return set(used)
    def wrap(self,window=15):
        cmf=((window-8)<<4)|8;flg=(-cmf*256)%31
        return bytes((cmf,flg))+self.bytes()+struct.pack('>I',zlib.adler32(self.raw))


def directed():
    cases=[]
    def add(name,b,window=15):cases.append((name,b.wrap(window),bytes(b.raw)))
    b=Bits();b.stored(b'',True);add('empty-stored',b)
    b=Bits();b.fixed([]);add('empty-fixed',b)
    b=Bits();b.fixed([17,(258,1)]);add('overlap-distance1-length258',b)
    b=Bits();b.stored(bytes((i*37+(i>>7))&255 for i in range(32768)));b.fixed([(258,32768),(3,32768)]);add('distance32768-cross-block',b)
    b=Bits();b.stored(bytes(range(256))*130);b.fixed([(258,257),(258,258),(3,3)]);add('history-wrap-overlap',b)
    b=Bits();b.fixed([65,(4,1)],False);b.stored(b'abcdef',False);b.fixed([(3,6),(258,1)]);add('fixed-stored-fixed-mixed',b)
    b=Bits();b.fixed(list(range(256))+[(n,1) for n in range(3,259)]);add('all-match-lengths',b)
    b=Bits();b.fixed(list(range(256))*128+[(3,d) for d in DIST_BASE]+[(258,32768)]);add('all-distance-bases',b)
    lengths=[0]*286
    for s in (0,1,2,3,11,256):lengths[s]=3
    lengths[285]=2;distances=[0]*30;distances[0]=distances[29]=1
    b=Bits();used=b.dynamic(lengths,distances,[11,(258,1),0,1,2,3]);assert used=={16,17,18};add('dynamic-repeat16-17-18',b)
    lengths=[0]*257;lengths[0]=lengths[256]=1
    b=Bits();b.dynamic(lengths,[0],[0],rle=False);add('dynamic-empty-distance-unused',b)
    lengths=[0]*257;lengths[256]=1
    b=Bits();b.dynamic(lengths,[0],[],rle=False);add('dynamic-single-eob',b)
    b=Bits();b.fixed([7,(258,1)]);add('small-window256',b,8)
    return cases
