#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: public_verify.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""Portable released-SV lint, BlackBox smoke, and source-to-RTL generation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
from source_headers import add, strip
from check_publication import check

ROOT = Path(__file__).resolve().parents[1]


def run(argv, cwd=ROOT, env=None):
    subprocess.run([str(x) for x in argv], cwd=cwd, env=env, check=True)


def released_lock(out):
    products, hashes = {}, {}
    for d in ('encode', 'decode'):
        base = ROOT / 'rtl' / d
        lines = (base / 'rtl.f').read_text().splitlines()
        products[d] = {'rtl': lines, 'assertions': lines}
        for name in [*lines, 'rtl.f']:
            hashes[d + '/' + name] = hashlib.sha256((base / name).read_bytes()).hexdigest()
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    data = {'status': 0, 'product_root': str(ROOT / 'rtl'), 'source_head': head,
            'products': products, 'sha256': hashes}
    path = out / 'released-input.json'
    path.write_text(json.dumps(data, indent=2) + '\n')
    return path, hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['lint', 'smoke', 'emit'])
    parser.add_argument('--out', default='build/public-smoke')
    parser.add_argument('--limit', type=int, default=2)
    args = parser.parse_args()
    check()
    if args.action == 'lint':
        for d, top in [('encode', 'PngEncoder'), ('decode', 'PngDecoder')]:
            run(['verilator', '--lint-only', '--assert', '--top-module', top, '-f', 'rtl.f'], ROOT / 'rtl' / d)
        print('PNG_RELEASED_LINT_PASS')
        return
    out = Path(args.out)
    assert not out.is_absolute() and '..' not in out.parts and out.parts[0] == 'build'
    out = ROOT / out
    out.mkdir(parents=True, exist_ok=False)
    if args.action == 'emit':
        env = dict(os.environ)
        env.pop('OPENIMAGE_PNG_EXTERNAL', None)
        env.pop('OPENIMAGE_PNG_EXTERNAL_SHA256', None)
        for direction, top in [('encode', 'PngEncoder'), ('decode', 'PngDecoder')]:
            target = out / direction
            run(shlex.split(os.environ.get('MILL', 'mill')) + ['-i', 'png.tool.runMain',
                'openimage.png.EmitProduct', direction, target], env=env)
            expected = ROOT / 'rtl' / direction
            for name in (expected / 'rtl.f').read_text().splitlines():
                fresh = target / Path(name).name
                assert fresh.read_bytes() == strip(name, (expected / name).read_bytes()), 'GENERATED_BODY: ' + name
                fresh.write_bytes(add(fresh.name, fresh.read_bytes()))
                assert fresh.read_bytes() == (expected / name).read_bytes()
        print('PNG_REGENERATED_BODY_AND_HEADER_PASS')
        return
    assert 1 <= args.limit <= 150
    lock, digest = released_lock(out)
    env = dict(os.environ, OPENIMAGE_PNG_EXTERNAL=str(lock), OPENIMAGE_PNG_EXTERNAL_SHA256=digest)
    for direction in ('encoder', 'decoder'):
        run([sys.executable, 'tools/verify_' + direction + '.py', '--out', str((out / direction).relative_to(ROOT)),
             '--jobs', '1', '--limit', str(args.limit)], env=env)
    run([sys.executable, 'tools/verify_decoder.py', '--out', str((out / 'decoder-negative').relative_to(ROOT)),
         '--jobs', '1', '--limit', '1', '--negative'], env=env)
    print('PNG_RELEASED_SMOKE_PASS: encoder/decoder/fixed-libpng/byte-negative')


if __name__ == '__main__':
    main()
