#!/usr/bin/env python3
# OpenRTL file declaration
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: png-codec
# Repository introduction: Standalone lossless PNG encoder and decoder.
# File: check_publication.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
"""Validate exact inventory, headers, original bodies and RTL closures."""
import hashlib
import json
from pathlib import Path
import re
from source_headers import strip

ROOT = Path(__file__).resolve().parents[1]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def check_public_text(data):
    # Generic user-directory patterns, never publisher-specific environment names.
    user_directory = rb'/(?:home|Users)/[A-Za-z0-9_.-]+/'
    secrets = rb'github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{25,}|-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY'
    assert not re.search(user_directory + b'|' + secrets, data), 'SECRET_OR_PRIVATE_PATH'


def check(root=ROOT):
    records = json.loads((root / 'SOURCE_MANIFEST.json').read_text())
    assert records['version'] == (root / 'VERSION').read_text().strip() == '1.2.1'
    names = {str(p.relative_to(root)) for p in root.rglob('*') if p.is_file()
             and not any(n in ('.git', 'build', 'out', 'dependencies', '__pycache__')
                         for n in p.relative_to(root).parts)}
    assert names == set(records['files']) | {'SOURCE_MANIFEST.json'}, 'INVENTORY'
    for name, record in records['files'].items():
        path = root / name
        assert not path.is_symlink()
        data = path.read_bytes()
        if record['declaration'] == 'inline':
            body = strip(name, data)
        else:
            body = data
            if record['declaration'] == 'companion':
                assert (root / (name + '.license')).is_file()
        assert sha(body) == record['body_sha256'], 'BODY_SHA: ' + name
        assert sha(data) == record['published_sha256'], 'FILE_SHA: ' + name
        check_public_text(data)
    for direction, top, count in [('encode', 'PngEncoder', 15), ('decode', 'PngDecoder', 24)]:
        base = root / 'rtl' / direction
        lines = (base / 'rtl.f').read_text().splitlines()
        assert len(lines) == len(set(lines)) == count
        for line in lines:
            assert line.endswith('.sv') and not Path(line).is_absolute() and '..' not in Path(line).parts
            text = (base / line).read_text()
            assert not re.search(r'\$readmem|\b(?:Suite|Capture)\b|\bDPI', text)
        assert any(re.search(r'^module\s+' + top + r'\b', (base / f).read_text(), re.M) for f in lines)
    # All local Markdown links (anchors handled separately by GitHub) must resolve.
    for file in root.rglob('*.md'):
        if '.git' in file.parts or 'build' in file.relative_to(root).parts:
            continue
        for target in re.findall(r'\]\(([^)]+)\)', file.read_text()):
            if target.startswith(('https://', 'http://', '#')):
                continue
            path = target.split('#')[0]
            assert (file.parent / path).is_file(), (file, target)
    return {'status': 0, 'files': len(names), 'original_bodies': sum(r['origin'] != 'publication' for r in records['files'].values()),
            'sv': 39, 'version': records['version']}


if __name__ == '__main__':
    print(json.dumps(check()))
