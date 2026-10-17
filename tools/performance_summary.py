#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: performance_summary.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""同一上升沿事件坐标的精确窗口汇总及已知事件自检。"""
from fractions import Fraction
import statistics

FREQUENCY=100000000

def summary(events,pixels,raw_bytes,file_bytes):
    assert events and pixels>0 and raw_bytes>0 and file_bytes>0
    for e in events:
        assert all(isinstance(e[k],int) and e[k]>=0 for k in ('a','u','v','p','q','c'))
        assert e['a']<=e['u']<=e['v']<=e['c'] and e['a']<=e['p']<=e['q']<=e['c']
        assert e['c']>e['a']
    for a,b in zip(events,events[1:]):assert b['a']>a['c'] and b['c']>a['c']
    n=len(events);lat=[e['c']-e['a'] for e in events]
    out={'tasks':n,'latency_cycles':{'first':lat[0],'min':min(lat),'median':statistics.median(lat),'max':max(lat),'mean':statistics.mean(lat)},
         'first_output_cycles':[e['p']-e['a'] for e in events],
         'last_output_cycles':[e['q']-e['a'] for e in events],
         'input_tail_cycles':[e['c']-e['v'] for e in events],
         'assumed_frequency_hz':FREQUENCY}
    def rates(count,cycles):
        assert cycles>0
        return {'window_cycles':cycles,'images':count,'pixels':pixels*count,'raw_bytes':raw_bytes*count,'file_bytes':file_bytes*count,
                'images_per_cycle':float(Fraction(count,cycles)), 'pixels_per_cycle':float(Fraction(pixels*count,cycles)),
                'raw_bytes_per_cycle':float(Fraction(raw_bytes*count,cycles)),'file_bytes_per_cycle':float(Fraction(file_bytes*count,cycles)),
                'images_per_second_at_100MHz':float(Fraction(count*FREQUENCY,cycles)),
                'MPixel_per_second_at_100MHz':float(Fraction(pixels*count*100,cycles)),
                'raw_MB_per_second_at_100MHz':float(Fraction(raw_bytes*count*100,cycles)),
                'file_MB_per_second_at_100MHz':float(Fraction(file_bytes*count*100,cycles))}
    out['batch']=rates(n,events[-1]['c']-events[0]['a'])
    if n>1:
        intervals=[b['c']-a['c'] for a,b in zip(events,events[1:])]
        out['sustained']=rates(n-1,sum(intervals))
        out['sustained'].update({'interval_min':min(intervals),'interval_max':max(intervals),
                                 'ii_numerator':sum(intervals),'ii_denominator':n-1,'ii_cycles':float(Fraction(sum(intervals),n-1))})
    return out

def selftest():
    events=[{'a':a,'u':a+1,'v':a+3,'p':a+2,'q':a+7,'c':a+9} for a in (10,30,50)]
    r=summary(events,7,2,11)
    assert r['sustained']['window_cycles']==40 and r['sustained']['pixels']==14 and r['sustained']['raw_bytes']==4
    assert r['sustained']['ii_cycles']==20 and r['sustained']['images_per_second_at_100MHz']==5000000
    assert r['sustained']['raw_MB_per_second_at_100MHz']==10 and r['sustained']['MPixel_per_second_at_100MHz']==35
    assert r['batch']['window_cycles']==49 and r['batch']['images']==3 and r['batch']['file_bytes']==33
    assert r['latency_cycles']['first']==9 and r['input_tail_cycles']==[6]*3
    fractional=summary([events[0],events[1],{k:(v+1 if k in ('a','u','v','p','q','c') else v) for k,v in events[2].items()}],7,2,11)
    assert fractional['sustained']['ii_numerator']==41 and fractional['sustained']['ii_denominator']==2
    assert fractional['sustained']['ii_cycles']==20.5
    assert fractional['sustained']['pixels_per_cycle']==float(Fraction(14,41))
    for bad in ([dict(events[0],c=10)], [events[0],dict(events[1],c=19)]):
        try:summary(bad,7,2,11)
        except AssertionError:pass
        else:raise AssertionError('零间隔/非法事件未拒绝')
    return {'status':0,'known_events':True,'n_minus_one_window':True,'units':True,'zero_interval_rejected':True}

if __name__=='__main__':
    import json
    print(json.dumps(selftest()))
