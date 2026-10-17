#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: export_performance_csv.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""从原始性能JSON/事件离线生成命名明确的CSV；不改写原记录或重跑RTL。"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
from performance_summary import summary,selftest

FIELDS=['case','direction','width','height','color_type','bit_depth','pattern','filter','mode',
        'per_task_pixels','per_task_raw_bytes','per_task_file_bytes','per_task_metadata_bytes',
        'single_latency_cycles','single_first_output_cycles','single_last_output_cycles','single_input_tail_cycles',
        'continuous_tasks','continuous_latency_min','continuous_latency_median','continuous_latency_max','continuous_latency_mean',
        'sustained_window_cycles','sustained_window_images','sustained_window_pixels','sustained_window_raw_bytes','sustained_window_file_bytes',
        'ii_numerator','ii_denominator','ii_cycles','interval_min','interval_max',
        'sustained_pixels_per_cycle','sustained_raw_bytes_per_cycle','sustained_file_bytes_per_cycle',
        'batch_window_cycles','batch_window_images','batch_window_pixels','batch_window_raw_bytes','batch_window_file_bytes',
        'batch_pixels_per_cycle','batch_raw_bytes_per_cycle','batch_file_bytes_per_cycle']

def rows(result):
    for case in result['configurations']:
        c=case['configuration'];single=case['single']
        row={k:c[k] for k in ('case','width','height','color_type','bit_depth','pattern','filter','mode')}
        row['direction']=result['direction']
        row.update({f'per_task_{k}':c[k] for k in ('pixels','raw_bytes','file_bytes','metadata_bytes')})
        row.update({'single_latency_cycles':single['latency_cycles']['first'],
                    'single_first_output_cycles':single['first_output_cycles'][0],
                    'single_last_output_cycles':single['last_output_cycles'][0],
                    'single_input_tail_cycles':single['input_tail_cycles'][0]})
        if 'continuous' in case:
            cont=case['continuous'];steady=cont['sustained'];batch=cont['batch']
            row['continuous_tasks']=cont['tasks']
            row.update({f'continuous_latency_{k}':cont['latency_cycles'][k] for k in ('min','median','max','mean')})
            for k in ('cycles','images','pixels','raw_bytes','file_bytes'):
                row['sustained_window_'+k]=steady['window_cycles' if k=='cycles' else k]
                row['batch_window_'+k]=batch['window_cycles' if k=='cycles' else k]
            row.update({k:steady[k] for k in ('ii_numerator','ii_denominator','ii_cycles','interval_min','interval_max')})
            for k in ('pixels_per_cycle','raw_bytes_per_cycle','file_bytes_per_cycle'):
                row['sustained_'+k]=steady[k];row['batch_'+k]=batch[k]
        yield row

def write_csv(path,result):
    with path.open('x') as stream:
        writer=csv.DictWriter(stream,fieldnames=FIELDS);writer.writeheader();writer.writerows(rows(result))

def check_csv(path,result,events):
    with path.open() as stream:
        reader=csv.DictReader(stream);assert reader.fieldnames==FIELDS;actual=list(reader)
    assert len(actual)==len(result['configurations'])
    comparisons=0
    def equal(text,value):
        nonlocal comparisons
        assert text!='' and math.isclose(float(text),value,rel_tol=1e-13,abs_tol=1e-15),(text,value)
        comparisons+=1
    for row,case in zip(actual,result['configurations']):
        c=case['configuration'];group=[e for e in events if e['case']==c['case']]
        assert [e['task'] for e in group]==list(range(c['batch_tasks']+1))
        assert row['direction']==result['direction']
        for k in ('case','width','height','color_type','bit_depth','filter'):equal(row[k],c[k])
        for k in ('pattern','mode'):assert row[k]==c[k]
        for k in ('pixels','raw_bytes','file_bytes','metadata_bytes'):equal(row['per_task_'+k],c[k])
        equal(row['single_latency_cycles'],group[0]['c']-group[0]['a'])
        equal(row['single_first_output_cycles'],group[0]['p']-group[0]['a'])
        equal(row['single_last_output_cycles'],group[0]['q']-group[0]['a'])
        equal(row['single_input_tail_cycles'],group[0]['c']-group[0]['v'])
        assert summary(group[:1],c['pixels'],c['raw_bytes'],c['file_bytes'])==case['single']
        if c['batch_tasks']:
            batch=group[1:];n=len(batch);span=batch[-1]['c']-batch[0]['c'];total=batch[-1]['c']-batch[0]['a']
            assert span>0 and total>0 and n==32
            assert summary(batch,c['pixels'],c['raw_bytes'],c['file_bytes'])=={k:v for k,v in case['continuous'].items() if k!='command_gap_cycles'}
            equal(row['continuous_tasks'],n);equal(row['sustained_window_cycles'],span);equal(row['sustained_window_images'],n-1)
            equal(row['ii_numerator'],span);equal(row['ii_denominator'],n-1);equal(row['ii_cycles'],span/(n-1))
            intervals=[b['c']-a['c'] for a,b in zip(batch,batch[1:])]
            equal(row['interval_min'],min(intervals));equal(row['interval_max'],max(intervals))
            for k in ('min','median','max','mean'):equal(row['continuous_latency_'+k],case['continuous']['latency_cycles'][k])
            equal(row['batch_window_cycles'],total);equal(row['batch_window_images'],n)
            for k in ('pixels','raw_bytes','file_bytes'):
                equal(row['sustained_window_'+k],c[k]*(n-1));equal(row['batch_window_'+k],c[k]*n)
                equal(row['sustained_'+k+'_per_cycle'],c[k]*(n-1)/span)
                equal(row['batch_'+k+'_per_cycle'],c[k]*n/total)
        else:assert all(row[k]=='' for k in FIELDS if k.startswith(('continuous_','sustained_','batch_','ii_','interval_')))
    return {'status':0,'rows':len(actual),'numeric_comparisons':comparisons,'configuration_and_event_windows_checked':True}

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--source',type=Path,action='append',required=True);ap.add_argument('--out',type=Path,required=True)
    args=ap.parse_args();args.out.mkdir(parents=True,exist_ok=False);records=[];selftest()
    for source in args.source:
        target=args.out/source.name;target.mkdir()
        completion=source/'completion.json';events=source/'events.json'
        original=source/('summary.csv' if (source/'summary.csv').exists() else 'summary-v2.csv')
        result=json.loads(completion.read_text());assert result['status']==0
        inputs={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (completion,events,original)}
        path=target/'summary-v2.csv';write_csv(path,result)
        checked=check_csv(path,result,json.loads(events.read_text()))
        # 主动重现三种错误汇总；不能只用同一个导出函数验证自己。
        negative_controls=[]
        if result['configurations'][0]['configuration']['batch_tasks']:
            with path.open() as stream:gold=list(csv.DictReader(stream))
            for key in ('per_task_raw_bytes','sustained_window_images','sustained_pixels_per_cycle'):
                tampered=[dict(row) for row in gold]
                tampered[0][key]=str(float(tampered[0][key])*2)
                negative=target/(key+'-negative.csv')
                with negative.open('x') as stream:
                    writer=csv.DictWriter(stream,fieldnames=FIELDS);writer.writeheader();writer.writerows(tampered)
                try:check_csv(negative,result,json.loads(events.read_text()))
                except AssertionError:negative_controls.append(key)
                else:raise AssertionError('CSV错误负控未拒绝：'+key)
        assert inputs=={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (completion,events,original)}
        record={'source':str(source),'source_head':result['head'],'input_sha256':inputs,'revised_csv':str(path),
                'revised_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'negative_controls_rejected':negative_controls,**checked}
        (target/'revision.json').write_text(json.dumps(record,indent=2)+'\n');records.append(record)
    (args.out/'completion.json').write_text(json.dumps({'status':0,'reason':'旧CSV同名字段被持续窗口累计量覆盖；新CSV明确单任务/持续窗口/整批，原JSON/事件/CSV均保留。','sources':records},indent=2)+'\n')
    print(json.dumps({'status':0,'sources':len(records),'rows':sum(r['rows'] for r in records),'originals_unchanged':True}))

if __name__=='__main__':main()
