#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: source_headers.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""Format-safe OpenRTL headers; only the exact added block is removable."""
import argparse
from pathlib import Path
import re

FIELDS = ('Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)',
          'Organization introduction: Open hardware, from algorithms to RTL.',
          'Repository: png-codec',
          'Repository introduction: Standalone lossless PNG encoder and decoder.',
          'Website: https://openrtl.top/',
          'SPDX-License-Identifier: MIT')


def header(path):
    path = Path(path)
    fields = (*FIELDS[:4], 'File: ' + path.name, *FIELDS[4:])
    if path.suffix == '.md':
        return ('<!-- OpenRTL file declaration\n' + '\n'.join(fields) + '\n-->\n').encode()
    prefix = '# ' if path.suffix in ('.py', '.sh') else '// '
    return (prefix + 'OpenRTL file declaration\n' +
            ''.join(prefix + f + '\n' for f in fields)).encode()


def split_prefix(path, data):
    # Keep shebang/encoding and all Mill launcher directives in their original position.
    lines = data.splitlines(keepends=True)
    n = 0
    if Path(path).suffix in ('.py', '.sh'):
        if lines and lines[0].startswith(b'#!'):
            n = 1
        if n < len(lines) and b'coding' in lines[n] and lines[n].lstrip().startswith(b'#'):
            n += 1
    elif str(path).endswith('.mill'):
        while n < len(lines) and lines[n].startswith(b'//|'):
            n += 1
    return b''.join(lines[:n]), b''.join(lines[n:])


def strip(path, data):
    prefix, body = split_prefix(path, data)
    expected = header(path)
    if not body.startswith(expected):
        raise ValueError('HEADER_MISSING_OR_CHANGED: ' + str(path))
    body = body[len(expected):]
    if re.search(rb'^(?:// |# |<!-- )OpenRTL file declaration$', body, re.M):
        raise ValueError('HEADER_DUPLICATE: ' + str(path))
    return prefix + body


def add(path, data):
    prefix, body = split_prefix(path, data)
    if body.startswith(header(path)):
        strip(path, data)
        return data
    if re.search(rb'^(?:// |# |<!-- )OpenRTL file declaration$', body, re.M):
        raise ValueError('HEADER_CHANGED: ' + str(path))
    return prefix + header(path) + body


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('files', nargs='+', type=Path)
    args = p.parse_args()
    for path in args.files:
        path.write_bytes(add(path, path.read_bytes()))


if __name__ == '__main__':
    main()
