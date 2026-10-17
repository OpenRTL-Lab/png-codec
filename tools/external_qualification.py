# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: external_qualification.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""Compile untouched independent PNG SV through a Chisel BlackBox boundary.

The opt-in lock and its SHA are explicit command inputs. No module rewriting,
renaming, test-driven constant specialization or automatic answer substitution.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT=Path(__file__).resolve().parents[1]

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def lock():
    name=os.environ.get('OPENIMAGE_PNG_EXTERNAL')
    if not name:return None
    path=Path(name).resolve()
    assert sha(path)==os.environ['OPENIMAGE_PNG_EXTERNAL_SHA256'],'qualified input lock changed'
    data=json.loads(path.read_text());assert data['status']==0
    root=Path(data['product_root']);assert root.is_absolute()
    for name,digest in data['sha256'].items():
        target=root/name
        assert target.resolve().is_relative_to(root.resolve())
        assert sha(target)==digest,'independent SV/list/bind changed: '+str(target)
    assert not subprocess.check_output(['git','diff',data['source_head'],'HEAD','--','src/main'],cwd=ROOT)
    return data

def product_files(rtl,top):
    data=lock()
    if not data or top not in ('PngEncoder','PngDecoder'):return None
    for file in rtl.glob('*.sv'):
        assert not re.search(r'^module\s+'+top+r'\b',file.read_text(),re.M), 'inline product body in qualified TB'
    direction='encode' if top=='PngEncoder' else 'decode'
    return [Path(data['product_root'])/direction/line for line in data['products'][direction]['rtl']]

def prepare_compile(command,out):
    if '--cc' not in command:return command,None
    data=lock()
    if not data:return command,None
    assert command[0]=='verilator'
    source_list=Path(command[command.index('-f')+1]);tb=[]
    for line in source_list.read_text().splitlines():
        if line.strip() and not line.startswith('+'):tb.append(Path(line))
    text='\n'.join(p.read_text() for p in tb)
    definitions=set(re.findall(r'^module\s+(\w+)',text,re.M))
    instances={name for name in ('PngEncoder','PngDecoder')
               if re.search(r'\b'+name+r'\s+\w+\s*\(',text)}
    if not instances:return command,None  # Unrelated component check, not PNG qualification.
    assert len(instances)==1
    top=next(iter(instances));assert top not in definitions,'testbench contains product implementation'
    direction='encode' if top=='PngEncoder' else 'decode'
    base=Path(data['product_root'])/direction
    lines=[];sources=[]
    for line in data['products'][direction]['assertions']:
        if line.startswith('+incdir+'):lines.append('+incdir+'+str(base/line[len('+incdir+'):]))
        else:lines.append(str(base/line));sources.append(base/line)
    names={n for p in sources for n in re.findall(r'^module\s+(\w+)',p.read_text(),re.M)}
    assert not names&definitions,'same-name independent/TB module collision'
    assert top in names
    target=out/'qualified-product.f';target.write_text('\n'.join(lines)+'\n')
    actual=[*command,'-f',str(target.resolve())]
    record={'status':'pending','direction':direction,'top':top,'product_source_head':data['source_head'],
            'lock':str(Path(os.environ['OPENIMAGE_PNG_EXTERNAL']).resolve()),
            'lock_sha256':os.environ['OPENIMAGE_PNG_EXTERNAL_SHA256'],
            'argv':[str(x) for x in actual],
            'independent_sources':{str(p):sha(p) for p in sources},
            'testbench_sources':{str(p.resolve()):sha(p) for p in tb},
            'unaltered_product_directly_compiled':True,'product_assertion_binds_included':False,
            'no_inline_product_body':True,'clocked_adapter':False}
    (out/'qualification-input.json').write_text(json.dumps(record,indent=2)+'\n')
    return actual,record

def finish_compile(out,record,returncode):
    if record is None:return
    lock()
    for section in ('independent_sources','testbench_sources'):
        for name,digest in record[section].items():assert sha(Path(name))==digest
    record={**record,'status':returncode,'hashes_unchanged_after_compile':True}
    (out/'qualification-compile.json').write_text(json.dumps(record,indent=2)+'\n')

def freeze(root,out):
    root=root.resolve();result=json.loads((root/'completion.json').read_text());assert result['status']==0
    products={}
    for direction in ('encode','decode'):
        base=root/direction
        rtl=(base/'rtl.f').read_text().splitlines();assertions=(base/'assertions.f').read_text().splitlines()
        for line in rtl:assert (base/line).is_file()
        products[direction]={'rtl':rtl,'assertions':assertions}
        expected=next(p for p in result['products'] if p['direction']==direction)['sha256']
        for name,digest in expected.items():assert sha(base/name)==digest
    files=[p for p in root.rglob('*') if p.is_file() and (p.suffix in ('.sv','.svh','.f') or p==root/'completion.json')]
    record={'status':0,'product_root':str(root),'source_head':result['head'],'products':products,
            'sha256':{str(p.relative_to(root)):sha(p) for p in sorted(files)}}
    with out.open('x') as f:f.write(json.dumps(record,indent=2)+'\n')
    return {'lock':str(out.resolve()),'sha256':sha(out),'files':len(files)}

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--freeze',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    print(json.dumps(freeze(a.freeze,a.out)))
