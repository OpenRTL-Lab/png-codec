#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: performance_contract.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""v1.2主集合和精确合同；纯离线函数，不把四舍五入的速率用作门禁。"""
from verify_rows import FORMATS


def main_choices(direction):
    assert direction in ('encode','decode')
    modes=range(2 if direction=='encode' else 3)
    out=[(256,256,0,8,p,f,m,32) for p in ('constant','gradient','noise')
         for f in range(5) for m in modes]
    for t,b in FORMATS:
        if (t,b)==(0,8):continue
        w,h=(1024,512) if b<8 else (256,256)
        out.extend((w,h,t,b,'gradient',f,m,32) for f in range(5) for m in modes)
    assert len(out)==(170 if direction=='encode' else 255)
    return out


def rate_pass(useful,cycles):
    assert useful>0 and cycles>0
    return 20*useful>=19*cycles


def check_main_result(result):
    """逐配置检查，不允许缺batch或只看平均速率。返回失败证据而非隐藏结果。"""
    failures=[]
    for item in result['configurations']:
        c=item['configuration'];case=c['contract_case']
        if not item['first_image_data']['pass']:failures.append([case,'first_image_data'])
        r=item['raw_rate_contract']
        if not r['single_pass']:failures.append([case,'cold_latency'])
        if not r.get('continuous_pass',False):failures.append([case,'continuous_31_intervals'])
        if c['batch_tasks']!=32:failures.append([case,'missing_32_task_batch'])
    return {'pass':not failures,'failed_checks':failures,
            'configurations':len(result['configurations']),'exact_fraction':[19,20]}


def selftest():
    from performance_vectors import filtered,metadata
    # Two 3-byte Gray8 rows; independently handwritten filter answers.
    raw=bytes([10,20,40,15,25,45])
    answers=([0,10,20,40,0,15,25,45], [1,10,10,20,1,15,10,20],
             [2,10,20,40,2,5,5,5], [3,10,15,30,3,10,8,13],
             [4,10,10,20,4,5,5,5])
    for f,answer in enumerate(answers):assert filtered(raw,3,2,0,8,f)==bytes(answer)
    for b in (1,2,4,8):
        data,p,t=metadata(3,b);assert p==3*(1<<b) and t==(1<<b)-1 and len(data)==p+t
    for d,n in [('encode',170),('decode',255)]:
        c=main_choices(d);assert len(c)==len(set(c))==n
        assert {(t,b) for _,_,t,b,*_ in c}==set(FORMATS)
        for w,h,t,b,p,f,m,batch in c:
            row=(w*{0:1,2:3,3:1,4:2,6:4}[t]*b+7)//8
            assert row>=64 and row*h>=65536 and batch==32
        assert {f for *_,f,m,batch in c}==set(range(5))
    assert rate_pass(19,20) and not rate_pass(19,21)
    # ceil(20*B/19) would incorrectly accept this 65536-byte boundary.
    b=65536;limit=20*b//19
    assert rate_pass(b,limit) and not rate_pass(b,limit+1)
    assert rate_pass(31*b,31*limit) and not rate_pass(31*b,20*31*b//19+1)
    return {'status':0,'encode_configurations':170,'decode_configurations':255,
            'all_15_formats_five_filters':True,'rounded_boundary_rejected':True,
            'five_filter_handwritten_answers':True,'indexed_palette_within_depth':True}


if __name__=='__main__':
    import json
    print(json.dumps(selftest()))
