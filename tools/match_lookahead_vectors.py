#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: match_lookahead_vectors.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""Original RFC token sequences for registered match lookahead; no DUT oracle."""
from inflate_vectors import Bits
from verify_huffman import canonical


def vectors():
    cases=[]
    def good(name,b):cases.append((name,b.wrap(),bytes(b.raw),0,None))
    for distance in (1,2,3,257,32768):
        b=Bits();b.stored(bytes((i*37+(i>>7))&255 for i in range(max(distance,3))))
        b.fixed([(3,distance)]*300+[(258,1),(3,2),(3,1),31])
        good(f'lookahead-chain-distance{distance}',b)
    b=Bits();b.fixed([17,18,(3,2),(3,1),(258,2)],False)
    b.stored(b'cross-block');b.fixed([(3,3)]*40)
    good('lookahead-eob-and-mixed-boundary',b)
    lengths=[0]*258;lengths[0]=1;lengths[256]=lengths[257]=2
    b=Bits();b.dynamic(lengths,[1,1],[0,(3,1)]+[(3,2)]*300+[0])
    good('lookahead-dynamic-len3-distance2',b)
    b=Bits();b.fixed([65,(3,1),(3,4),(3,7),(258,1)])
    good('lookahead-distance-in-current-uncommitted-copy',b)
    ll=canonical([8]*144+[9]*112+[7]*24+[8]*8);dd=canonical([5]*32)
    for kind in ('reserved-literal','reserved-distance','distance-too-far','truncated-distance'):
        b=Bits();b.put(1,1);b.put(1,2)
        b.symbol(65,ll);b.raw.append(65)
        # Valid current copy must not be overwritten by speculative next token.
        b.symbol(257,ll);b.symbol(0,dd);b.raw.extend(b'AAA')
        if kind=='reserved-literal':b.symbol(286,ll)
        else:
            b.symbol(257,ll)
            if kind=='reserved-distance':b.symbol(30,dd)
            elif kind=='distance-too-far':b.symbol(4,dd);b.put(0,1)
            else:
                # No following Adler bytes: input exhaustion must not deadlock.
                z=bytes.fromhex('7801')+b.bytes()
                # Byte padding can form distance1; permit its correct prefix,
                # but require malformed status because the checksum is absent.
                cases.append((f'lookahead-{kind}',z,b'A'*7,3,7));continue
        cases.append((f'lookahead-{kind}',b.wrap(),bytes(b.raw),3,7))
    return cases
