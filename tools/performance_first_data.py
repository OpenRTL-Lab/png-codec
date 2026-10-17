#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: performance_first_data.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""实际编码文件首图像token→外部输出拍计时，不把签名/滤波当图像。"""
from inspect_deflate import BASES, EXTRAS, DIST_BASE, DIST_EXTRA
from verify_huffman import canonical


def first_image_offset(png):
    assert png[:8] == b'\x89PNG\r\n\x1a\n'
    compressed=bytearray(); locations=[]; cursor=8
    while cursor<len(png):
        n=int.from_bytes(png[cursor:cursor+4],'big'); name=png[cursor+4:cursor+8]
        assert cursor+12+n<=len(png)
        if name==b'IDAT':
            compressed.extend(png[cursor+8:cursor+8+n])
            locations.extend(range(cursor+8,cursor+8+n))
        cursor+=n+12
    z=bytes(compressed); assert len(z)>=6 and int.from_bytes(z[:2],'big')%31==0
    body=z[2:-4]; bit=0; produced=0
    def take(n):
        nonlocal bit
        assert bit+n<=len(body)*8
        value=sum((body[(bit+i)//8]>>((bit+i)%8)&1)<<i for i in range(n))
        bit+=n;return value
    lengths=[8]*144+[9]*112+[7]*24+[8]*8
    codes={tuple(v):s for s,v in canonical(lengths).items()}
    def symbol():
        prefix=[]
        for _ in range(9):
            prefix.append(take(1))
            if tuple(prefix) in codes:return codes[tuple(prefix)]
        raise AssertionError('非法FIXED首token')
    while True:
        final=take(1); kind=take(2)
        assert kind in (0,1),'产品编码模式不应为DYNAMIC/保留块'
        if kind==0:
            bit=(bit+7)//8*8;n=take(16);assert n^take(16)==65535
            while n and produced<2:
                take(8); produced+=1;n-=1
            if produced>=2:break
        else:
            while produced<2:
                s=symbol()
                if s<256:produced+=1
                elif s==256:break
                else:
                    assert 257<=s<=285
                    n=BASES[s-257]+take(EXTRAS[s-257]);d=0
                    for _ in range(5):d=(d<<1)|take(1)
                    assert d<30
                    distance=DIST_BASE[d]+take(DIST_EXTRA[d])
                    assert distance<=produced
                    produced+=n
            if produced>=2:break
        assert not final,'PNG必须包含滤波字节和实际像素'
    # The last compressed byte required to decode the second filtered byte.
    # If a match supplies it, all length/distance bits must have arrived too.
    zi=2+(bit+7)//8-1
    return {'file_offset':locations[zi], 'zlib_byte':zi, 'deflate_end_bit':bit,
            'decoded_prefix_bytes':produced}


def attach(events,prefixes,targets,direction):
    out=[]
    for event in events:
        e=dict(event)
        if direction=='decode':e['image']=e['p']
        else:
            target=targets[e['case']]['file_offset']
            match=[p for p in prefixes if p['case']==e['case'] and p['task']==e['task']
                   and p['offset']<=target<p['offset']+p['bytes']]
            assert len(match)==1,'首图像token必须有唯一实际外部握手'
            p=match[0];assert 1<=p['bytes']<=8
            assert e['a']<=p['cycle']<=e['q']<=e['c'],'首图像周期必须在任务窗口内'
            e['image']=p['cycle']
        out.append(e)
    return out


def selftest():
    from inflate_vectors import Bits
    from performance_vectors import png_file, chunk
    fixtures=[]
    b=Bits();b.stored(b'\0\1',True);fixtures.append((b.wrap(),8))
    b=Bits();b.fixed([0,255]);fixtures.append((b.wrap(),4))
    b=Bits();b.fixed([0,(257,1)]);fixtures.append((b.wrap(),5))
    for z,index in fixtures:
        png=png_file(1,1,0,8,b'',0,0,z)
        target=first_image_offset(png);assert target['zlib_byte']==index
        # IDAT boundaries may split a Huffman code, and must not reset parsing.
        fragmented=png[:33]+b''.join(chunk(b'IDAT',bytes([v])) for v in z)+chunk(b'IEND',b'')
        split=first_image_offset(fragmented)
        assert split['zlib_byte']==index and split['file_offset']==41+13*index
        e={'case':0,'task':0,'a':10,'p':20,'q':100,'c':102}
        p={'case':0,'task':0,'offset':target['file_offset'],'bytes':1,'cycle':30}
        assert attach([e],[p],{0:target},'encode')[0]['image']==30
        for bad in (dict(p,offset=0),dict(p,cycle=9)):
            try:attach([e],[bad],{0:target},'encode')
            except AssertionError:pass
            else:raise AssertionError('首数据目标/周期负控未拒绝')
    return {'status':0,'fixtures':3,'fragmented_idat':True,'offset_and_cycle_negatives':True}


if __name__=='__main__':
    import json
    print(json.dumps(selftest()))
