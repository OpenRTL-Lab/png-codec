#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: verify_performance.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""完整PNG单任务/32连续任务真实周期，固定参考及指定断言负控。"""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import resource
import shlex
import subprocess
from performance_vectors import raw_image,metadata,filtered,png_file,parse_deflate,forced_stream,SEED
from performance_summary import summary,selftest
from performance_first_data import first_image_offset,attach,selftest as first_data_selftest
from export_performance_csv import write_csv,check_csv
from verify_encoder import check_file
from verify_inflate import product_closure
from verify_sample import ROOT,require_frozen,run
from performance_contract import main_choices,check_main_result,rate_pass,selftest as contract_selftest
from performance_supplement import small_choices,pressure_choices,pressure_stream,pressure_png,PRESSURE

def choices(direction,smoke=False):
    # W H type depth pattern filter mode batchN
    if smoke:return [(17,9,0,8,'gradient',4,1 if direction=='encode' else 2,32),
                     (17,9,3,8,'gradient',4,1 if direction=='encode' else 2,32)]
    if direction=='encode':
        out=[(256,256,0,8,p,f,m,32) for p in ('constant','gradient','noise') for f in (0,4) for m in (0,1)]
    else:out=[(256,256,0,8,p,0,m,32) for p in ('constant','gradient','noise') for m in (0,1,2)]
    out += [(256,256,t,b,'gradient',4,1 if direction=='encode' else 2,32) for t,b in ((0,1),(3,8),(6,8),(6,16))]
    out += [(n,n,0,8,'gradient',4,1 if direction=='encode' else 2,0) for n in (64,1024)]
    return out

def digest(data):return hashlib.sha256(data).hexdigest()

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',required=True)
    ap.add_argument('--direction',choices=['encode','decode'],required=True);ap.add_argument('--jobs',type=int,default=2)
    ap.add_argument('--smoke',action='store_true');ap.add_argument('--reuse-emission');ap.add_argument('--emission-commit')
    ap.add_argument('--main-matrix',action='store_true',help='冻结170编码/255解码配置，逐配置冷启动和32连续合同')
    ap.add_argument('--case-range',help='主集合半开START:STOP；保留全局contract_case索引，不能替代完整覆盖')
    ap.add_argument('--supplement',choices=('small','pressure'),help='独立小图/极端集合，绝不替代正常主集合')
    ap.add_argument('--product-base',default='9f28a9a',help='本次待测产品冻结提交；禁止把旧基线不变断言静默移除')
    args=ap.parse_args();os.chdir(ROOT);require_frozen()
    head=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    product_base=subprocess.check_output(['git','rev-parse',args.product_base+'^{commit}'],text=True).strip()
    assert not subprocess.check_output(['git','diff',product_base,'HEAD','--','src/main'])
    resource.setrlimit(resource.RLIMIT_CORE,(0,0));os.environ['CCACHE_DISABLE']='1'
    out=Path(args.out)
    if out.is_absolute() or '..' in out.parts or out.parts[0]!='build':raise ValueError('输出为build新目录')
    out.mkdir(parents=True,exist_ok=False);vectors=out/'vectors';vectors.mkdir()
    (out/'counter-selftest.json').write_text(json.dumps(selftest(),indent=2)+'\n')
    (out/'first-data-selftest.json').write_text(json.dumps(first_data_selftest(),indent=2)+'\n')
    (out/'contract-selftest.json').write_text(json.dumps(contract_selftest(),indent=2)+'\n')
    prefix=ROOT/'dependencies/install';pnglib=next(p for p in (prefix/'lib64/libpng16.a',prefix/'lib/libpng16.a') if p.is_file())
    for name,source,libs in [('checker','encoder_check.c',[pnglib,prefix/'lib/libz.a','-lm']),('zlib-reference','zlib_reference.c',[prefix/'lib/libz.a'])]:
        run([os.environ.get('CC','cc'),'-O2','-std=c11','-Wall','-Wextra','-Werror',
             '-I'+str(prefix/'include'),ROOT/'reference'/source,*libs,'-o',out/name],out,name+'-compile')
    records=[];cases=[];all_raw=bytearray();all_meta=bytearray();all_png=bytearray()
    assert not (args.main_matrix and args.smoke)
    assert not args.supplement or not (args.main_matrix or args.smoke or args.case_range)
    if args.supplement=='pressure':assert args.direction=='decode'
    specs=(small_choices(args.direction) if args.supplement=='small' else pressure_choices() if args.supplement=='pressure'
           else main_choices(args.direction) if args.main_matrix else choices(args.direction,args.smoke))
    start,stop=0,len(specs)
    if args.case_range:
        assert args.main_matrix
        start,stop=map(int,args.case_range.split(':'))
        assert 0<=start<stop<=len(specs)
    interned=[{} for _ in range(3)]
    def store(data,buffer,table):
        key=digest(data)
        if key not in table:
            table[key]=len(buffer);buffer.extend(data)
        return table[key]
    for index,(w,h,t,b,pattern,f,m,batch) in enumerate(specs[start:stop]):
        case=vectors/f'case-{index:02d}';case.mkdir()
        raw=raw_image(w,h,t,b,pattern);meta,plen,tlen=metadata(t,b)
        if args.supplement=='pressure':raw=pressure_stream(PRESSURE[index])[0]
        (case/'raw.bin').write_bytes(raw);(case/'meta.bin').write_bytes(meta)
        record={'case':index,'width':w,'height':h,'color_type':t,'bit_depth':b,'pattern':pattern,
                'filter':f,'mode':('STORED','FIXED','DYNAMIC')[m],'batch_tasks':batch,'pixels':w*h,'raw_bytes':len(raw),
                'palette_bytes':plen,'transparency_bytes':tlen,'metadata_bytes':len(meta),'pattern_seed':SEED if pattern=='noise' else None,
                'raw_sha256':digest(raw),'metadata_sha256':digest(meta)}
        if args.main_matrix:record['contract_case']=start+index
        png=b'\0\0'
        if args.direction=='decode':
            expected=filtered(raw,w,h,t,b,f);(case/'filtered.bin').write_bytes(expected)
            run([out/'zlib-reference','compress',case/'filtered.bin',case/'software.zlib',m],out,f'compress-{index:02d}')
            compressed=(case/'software.zlib').read_bytes();actual=parse_deflate(compressed,expected)
            source='fixed-zlib1.3.2'
            if set(actual['block_types'])!={m}:
                compressed=forced_stream(expected,m);actual=parse_deflate(compressed,expected)
                source='original-RFC-constructor-forced-type'
            if args.supplement=='pressure':
                _,expected,compressed=pressure_stream(PRESSURE[index]);actual=parse_deflate(compressed,expected)
                source='original-RFC-pressure-constructor'
                record['pressure_case']=PRESSURE[index]
            else:assert set(actual['block_types'])=={m}
            (case/'input.zlib').write_bytes(compressed)
            run([out/'zlib-reference','check',case/'input.zlib',case/'filtered.bin'],out,f'zlib-check-{index:02d}')
            png=pressure_png(PRESSURE[index],compressed) if args.supplement=='pressure' else png_file(w,h,t,b,meta,plen,tlen,compressed)
            (case/'input.png').write_bytes(png)
            run([out/'checker',case/'input.png',case/'raw.bin',case/'meta.bin',w,h,t,b,plen,tlen],out,f'input-check-{index:02d}')
            record.update({'file_bytes':len(png),'file_sha256':digest(png),'deflate_source':source,**actual})
        if args.main_matrix:
            ro=store(raw,all_raw,interned[0]);mo=store(meta,all_meta,interned[1]);po=store(png,all_png,interned[2])
        else:
            ro,mo,po=len(all_raw),len(all_meta),len(all_png)
            all_raw+=raw;all_meta+=meta;all_png+=png
        cases.append([w,h,t,b,f,m,len(raw),ro,plen,tlen,mo,len(png),po,batch]);records.append(record)
    for name,data in [('raw.hex',all_raw),('png.hex',all_png),('meta.hex',all_meta)]:
        (vectors/name).write_text(''.join(f'{v:02x}\n' for v in bytes(data).ljust(2,b'\0')))
    (vectors/'cases.txt').write_text(''.join(' '.join(map(str,c))+'\n' for c in cases))
    (out/'configurations.json').write_text(json.dumps(records,indent=2)+'\n')
    rtl=out/'rtl'
    if args.reuse_emission:
        assert args.emission_commit
        previous=Path(args.reuse_emission)
        for name in ('raw.hex','png.hex','meta.hex','cases.txt'):assert (previous/'vectors'/name).read_bytes()==(vectors/name).read_bytes()
        assert not subprocess.check_output(['git','diff',args.emission_commit,'HEAD','--','src','build.mill'])
        # Capture path is embedded; rerun must not overwrite the prior result.
        raise ValueError('性能顶层含固定捕获目录，不跨结果目录复用；相同冻结生成请直接保留原任务')
    run(shlex.split(os.environ.get('MILL','mill'))+['-i','png.tool.runMain','openimage.png.EmitPerformance',rtl,args.direction,vectors,out],out,'emit')
    files=[rtl/p for p in (rtl/'filelist.f').read_text().splitlines() if p.strip()]
    binds=sorted((rtl/'verification/assert').glob('layers-*-Verification-Assert.sv'));assert binds
    (out/'simulation.f').write_text('+define+ENABLE_INITIAL_MEM_\n+incdir+'+str(ROOT/rtl/'verification')+'\n+incdir+'+str(ROOT/rtl/'verification/assert')+'\n'+''.join(str(ROOT/p)+'\n' for p in files+binds))
    top='PngEncoder' if args.direction=='encode' else 'PngDecoder';product=product_closure(rtl,top)
    assert all('$readmem' not in p.read_text() and 'PerformanceCapture' not in p.read_text() for p in product)
    run(['verilator','--lint-only','--assert','--top-module',top,*product],out,'product-lint')
    run(['verilator','--cc','--exe','--build','--assert','--top-module','PerformanceSuite','--Mdir',out/'obj','-j',args.jobs,
         '-CFLAGS','-O2 -std=c++17','-f',out/'simulation.f',ROOT/'sim/performance_clock_main.cpp'],out,'verilator-build')
    binary=out/'obj/VPerformanceSuite'
    run([binary],out,'simulation')
    log=(out/'simulation.log').read_text();assert 'PNG_PERF_SUITE_PASS' in log
    (out/'simulation-exit.json').write_text(json.dumps({'returncode':0})+'\n')
    events=[{k:int(v) for k,v in re.findall(r'(\w+)=\s*(\d+)',line)} for line in log.splitlines() if line.startswith('PNG_PERF_EVENT')]
    assert len(events)==sum(c[13]+1 for c in cases)
    prefixes=[{k:int(v) for k,v in re.findall(r'(\w+)=\s*(\d+)',line)} for line in log.splitlines() if line.startswith('PNG_PERF_PREFIX')]
    targets={i:first_image_offset((out/f'observed-{i:02d}.png').read_bytes())
             for i in range(len(cases))} if args.direction=='encode' else {}
    events=attach(events,prefixes,targets,args.direction)
    (out/'first-data.json').write_text(json.dumps({'targets':targets,'prefix_handshakes':prefixes},indent=2)+'\n')
    (out/'events.json').write_text(json.dumps(events,indent=2)+'\n')
    with (out/'events.csv').open('w') as stream:
        writer=csv.DictWriter(stream,fieldnames=list(events[0]));writer.writeheader();writer.writerows(events)
    results=[]
    for i,(cfg,record) in enumerate(zip(cases,records)):
        group=[e for e in events if e['case']==i];assert [e['task'] for e in group]==list(range(cfg[13]+1))
        assert all(e['pixels']==record['pixels'] and e['metadata']==record['metadata_bytes'] and e['tag']==0xce000000+64*i+e['task'] for e in group)
        if args.direction=='encode':
            actual=(out/f'observed-{i:02d}.png').read_bytes()
            run([out/'checker',out/f'observed-{i:02d}.png',vectors/f'case-{i:02d}/raw.bin',vectors/f'case-{i:02d}/meta.bin',
                 cfg[0],cfg[1],cfg[2],cfg[3],cfg[8],cfg[9]],out,f'output-check-{i:02d}')
            checked=check_file(actual,cfg[:11],(vectors/f'case-{i:02d}/raw.bin').read_bytes(),(vectors/f'case-{i:02d}/meta.bin').read_bytes())
            record.update({'file_bytes':len(actual),'file_sha256':digest(actual),**checked})
        assert all(e['input']==(record['raw_bytes'] if args.direction=='encode' else record['file_bytes']) and
                   e['output']==(record['file_bytes'] if args.direction=='encode' else record['raw_bytes']) for e in group)
        result={'configuration':record,'single':summary(group[:1],record['pixels'],record['raw_bytes'],record['file_bytes'])}
        if cfg[13]:
            result['continuous']=summary(group[1:],record['pixels'],record['raw_bytes'],record['file_bytes'])
            result['continuous']['command_gap_cycles']=[b['a']-a['c'] for a,b in zip(group[1:],group[2:])]
            assert result['continuous']['command_gap_cycles']==[1]*(cfg[13]-1),'测试不应引入额外任务间隙'
        first=[e['image']-e['a'] for e in group]
        row_bytes=(cfg[0]*{0:1,2:3,3:1,4:2,6:4}[cfg[2]]*cfg[3]+7)//8
        budget=(record['metadata_bytes']+row_bytes+64 if args.direction=='encode' else
                record['metadata_bytes']+(128,256,4096)[cfg[5]])
        result['first_image_data']={'single_cycles':first[0],'all_task_cycles':first,
                                    'budget_cycles':budget,'pass':all(n<=budget for n in first)}
        if record['raw_bytes']>=65536 and row_bytes>=64:
            single_cycles=group[0]['c']-group[0]['a']
            result['raw_rate_contract']={'numerator':19,'denominator':20,
                'single_pass':rate_pass(record['raw_bytes'],single_cycles)}
            if cfg[13]:
                span=group[-1]['c']-group[1]['c']
                result['raw_rate_contract']['continuous_pass']=rate_pass((cfg[13]-1)*record['raw_bytes'],span)
        if args.supplement:
            cold=group[0]['c']-group[0]['a'];span=group[-1]['c']-group[1]['c'];useful=record['raw_bytes']
            if args.supplement=='small':
                full=useful+cfg[1]
                limit=(100*full+97)//98+record['metadata_bytes']+(2304 if args.direction=='encode' else 4096)
                if cfg[:4]==[64,64,0,8] and (args.direction=='encode' and cfg[5]==1 or args.direction=='decode' and cfg[5]==2):
                    limit=min(limit,5000 if args.direction=='encode' else 7000)
                result['supplement_contract']={'kind':'small_latency_and_ii','limit_cycles':limit,
                    'single_pass':cold<=limit,'continuous_pass':span<=31*limit,
                    'first_pass':result['first_image_data']['pass']}
            else:
                floor=(record['file_bytes']+7)//8
                result['supplement_contract']={'kind':'pressure_89_percent_proposal','exact_fraction':[89,100],
                    'single_pass':100*useful>=89*cold,'continuous_pass':100*31*useful>=89*span,
                    'native_input_lower_bound_cycles':floor,'native_input_optimistic_upper_rate':useful/floor,
                    'proposal_impossible_at_native_input':100*useful<89*floor,
                    'first_data_is_report_only':True,'first_pass':True}
        results.append(result)
    # 同一个真实模型、仅篡改期望tag的运行触发，不重新编译或更改产品。
    p=subprocess.run([binary,'--negative'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    text=p.stdout.decode('utf-8',errors='replace');(out/'negative.log').write_text(text)
    assert p.returncode!=0 and 'PNG_PERF_TAG_NEGATIVE' in text,'必须命中指定比较器'
    (out/'negative-exit.json').write_text(json.dumps({'returncode':p.returncode,'designated_assertion':'PNG_PERF_TAG_NEGATIVE'})+'\n')
    if args.direction=='encode':
        for r in records:assert digest((out/f'observed-{r["case"]:02d}.png').read_bytes())==r['file_sha256']
    require_frozen();assert head==subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    result={'status':0,'head':head,'product_base':product_base,'direction':args.direction,'smoke':args.smoke,
            'threads_per_simulation':1,'ideal_input_output':True,'counter_selftest':True,'negative_pass':True,
            'reference':'libpng1.6.59/zlib1.3.2','assumed_frequency_hz':100000000,'not_fmax':True,'configurations':results}
    if args.main_matrix:
        result['main_matrix']={'total_configurations':len(specs),'range':[start,stop],
                               'complete_coverage':start==0 and stop==len(specs)}
        result['performance_contract']=check_main_result(result)
        result['status']=int(not result['performance_contract']['pass'])
    if args.supplement:
        failed=[x['configuration']['case'] for x in results if not all(x['supplement_contract'][k] for k in ('single_pass','continuous_pass','first_pass'))]
        result['supplement_profile']=args.supplement
        result['performance_contract']={'pass':not failed,'failed_contract_cases':failed,'normal_main_coverage':False}
        result['status']=int(bool(failed))
    write_csv(out/'summary-v2.csv',result)
    check_csv(out/'summary-v2.csv',result,events)
    (out/'completion.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k!='configurations'}))
    raise SystemExit(result['status'])

if __name__=='__main__':main()
