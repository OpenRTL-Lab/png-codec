#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: prepare_reference.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""获取固定独立参考并构建本地静态库；源码和产物仅位于忽略目录。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    'zlib-1.3.2': ('https://zlib.net/zlib-1.3.2.tar.gz',
                   'bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16'),
    'libpng-1.6.59': ('https://codeload.github.com/pnggroup/libpng/tar.gz/refs/tags/v1.6.59',
                      '2540302a1844ad2b2b501977abecfa850f265f97b78f065a712ab4074a89f5b5'),
}


def run(cmd, cwd, log, env=None):
    with log.open('ab') as f:
        p = subprocess.run([str(x) for x in cmd], cwd=cwd, env=env,
                           stdout=f, stderr=subprocess.STDOUT)
    if p.returncode:
        raise RuntimeError(f'参考构建失败：{log.name}，退出{p.returncode}')


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--fetch-only', action='store_true')
    ap.add_argument('--jobs', type=int, default=4)
    args = ap.parse_args()
    deps = ROOT / 'dependencies'
    deps.mkdir(exist_ok=True)
    metadata = {}
    for name, (url, digest) in SOURCES.items():
        archive = deps / (name + '.tar.gz')
        if not archive.exists():
            with urllib.request.urlopen(url, timeout=90) as response:
                data = response.read()
            if digest and hashlib.sha256(data).hexdigest() != digest:
                raise RuntimeError('参考归档摘要不一致：' + name)
            tmp = archive.with_suffix('.download')
            tmp.write_bytes(data)
            tmp.replace(archive)
        actual = hashlib.sha256(archive.read_bytes()).hexdigest()
        if digest and actual != digest:
            raise RuntimeError('已缓存参考不一致：' + name)
        dest = deps / name
        if not dest.exists():
            with tarfile.open(archive, 'r:gz') as tar:
                for member in tar.getmembers():
                    parts = Path(member.name).parts
                    if not parts or parts[0] != name or '..' in parts or member.issym() or member.islnk():
                        raise RuntimeError('参考归档含非预期路径／链接')
                tar.extractall(deps)
        metadata[name] = {'url': url, 'sha256': actual}
    (deps / 'sources.json').write_text(json.dumps(metadata, indent=2) + '\n')
    if args.fetch_only:
        print('固定参考源已获取；未提交第三方源码。')
        return
    prefix = deps / 'install'
    if any((prefix / d / 'libpng16.a').exists() for d in ('lib','lib64')) and (prefix / 'lib/libz.a').exists():
        print('复用既有固定参考静态库。')
        return
    log = deps / 'build.log'
    z = deps / 'zlib-1.3.2'
    run(['./configure', '--static', '--prefix=' + str(prefix)], z, log)
    run(['make', '-j' + str(args.jobs)], z, log)
    run(['make', 'install'], z, log)
    png_build = deps / 'png-build'
    run(['cmake', '-S', deps / 'libpng-1.6.59', '-B', png_build,
         '-DCMAKE_BUILD_TYPE=Release', '-DPNG_SHARED=OFF', '-DPNG_STATIC=ON',
         '-DPNG_TESTS=OFF', '-DPNG_TOOLS=OFF', '-DZLIB_ROOT=' + str(prefix),
         '-DCMAKE_INSTALL_PREFIX=' + str(prefix)], ROOT, log)
    run(['cmake', '--build', png_build, '--parallel', args.jobs], ROOT, log)
    run(['cmake', '--install', png_build], ROOT, log)
    print('固定libpng/zlib静态参考已构建。')


if __name__ == '__main__':
    main()
