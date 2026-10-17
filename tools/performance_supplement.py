# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: performance_supplement.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""冻结的小图与极端完整文件刺激；只用于离线独立参考与真实RTL测量。"""
import struct
from inflate_vectors import Bits
from performance_vectors import canonical,forced_stream,filtered,png_file,chunk,parse_deflate

PRESSURE=('literal15','short-match3','overlap1','window32768','mixed-blocks','idat-single-byte')

def small_choices(direction):
    sizes=((1,1,0,1),(17,9,0,8),(64,64,0,8),(1,8192,0,1),
           (8192,1,0,1),(8192,1,6,16),(8191,1,2,16))
    modes=range(2 if direction=='encode' else 3)
    return [(w,h,t,b,'gradient',4,m,32) for w,h,t,b in sizes for m in modes]

def pressure_choices():
    return [(256,256,0,8,'constant',0,2 if i in (0,5) else 1,32) for i in range(6)]

def zero_matches(n,length,distance):
    out=[]
    while n>=3:
        k=min(length,n);out.append((k,distance));n-=k
    return out+[0]*n

def pressure_stream(name):
    raw=bytes(65536);expected=filtered(raw,256,256,0,8,0)
    b=Bits()
    if name=='literal15':
        # Split the old length-eight zero leaf into a complete seven-level
        # chain. Unused length symbols are the sibling leaves, never tokens.
        ll=[8]*255+[9,9]+list(range(9,16));ll[0]=15
        assert len(ll)==264 and sum(1<<(15-n) for n in ll)==32768
        cl=[4]*16+[0]*3;order=(16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15)
        b.put(1,1);b.put(2,2);b.put(len(ll)-257,5);b.put(0,5);b.put(15,4)
        for s in order:b.put(cl[s],3)
        for n in ll+[1]:b.symbol(n,canonical(cl))
        b.tokens(expected,canonical(ll),canonical([1]))
    elif name in ('short-match3','overlap1'):
        length=3 if name=='short-match3' else 258
        b.fixed([0]+zero_matches(len(expected)-1,length,1))
    elif name=='window32768':
        b.fixed([0]*32768+zero_matches(len(expected)-32768,258,32768))
    elif name=='mixed-blocks':
        b.stored(expected[:32768],False)
        b.fixed(zero_matches(len(expected)-32768,258,1))
    else:
        assert name=='idat-single-byte'
        return raw,expected,forced_stream(expected,2)
    assert bytes(b.raw)==expected
    return raw,expected,b.wrap()

def pressure_png(name,compressed):
    if name!='idat-single-byte':return png_file(256,256,0,8,b'',0,0,compressed)
    return (b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',256,256,8,0,0,0,0))+
            b''.join(chunk(b'IDAT',bytes([v])) for v in compressed)+chunk(b'IEND',b''))

def selftest():
    import zlib
    out=[]
    for name in PRESSURE:
        raw,expected,z=pressure_stream(name)
        parsed=parse_deflate(z,expected)
        assert zlib.decompress(z)==expected
        out.append({'name':name,'raw_bytes':len(raw),'zlib_bytes':len(z),
                    'png_bytes':len(pressure_png(name,z)),**parsed})
    return out

if __name__=='__main__':
    import json
    print(json.dumps(selftest()))
