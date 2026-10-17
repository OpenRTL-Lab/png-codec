<!-- OpenRTL file declaration
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: png-codec
Repository introduction: Standalone lossless PNG encoder and decoder.
File: reproduction.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# Reproduction

This guide lets an RTL developer check distributed circuit files, regenerate
them from Chisel and optionally run larger test sets. Start with lightweight
checks and short simulation; the extended tests are separate, more costly runs.
Every `--out` below names a new directory under `build/`; do not reuse an
existing run directory. Build products and logs stay there, not in source files.

[简体中文](reproduction.zh-CN.md)

Commands run from the cloned repository root. Requirements: Linux, Git,
Python 3.12, JDK 21, Mill 1.1.7, Scala 2.13.18, Chisel 7.13.0, firtool 1.149.0,
Verilator 5.050, Make/CMake and a C/C++17 compiler. Historical GCC was 16.2.0.
Other versions are not guaranteed byte-identical. `MILL` can name your own
launcher command, e.g. `export MILL='bash /your/tools/mill'`. No private path is
required. Official acquisition links and licenses: [Third-party notices](../THIRD_PARTY_NOTICES.md).
Java/Scala/Mill/Chisel can require internet on first use; there is no bundled
offline toolchain. Dependency download failures are not RTL results.

## Lightweight checks

```sh
python3 tools/check_publication.py
python3 tools/test_source_headers.py
python3 tools/public_verify.py lint
python3 tools/prepare_reference.py --jobs 1
```

The first two check complete inventory, every added declaration and exact body
SHA, plus missing/duplicate/corrupt header, changed-body and extra-file controls.
They are not hardware simulation. Lint runs the two released relative lists
independently with assertions enabled; it is parsing, not synthesis.
Reference preparation downloads pinned public archives, verifies SHA-256 and
builds static libpng/zlib locally. Only build artifacts are ignored, not source
changes. On a new host, install the prerequisites separately; check logs instead
of claiming a `--help` call bootstrapped them.

## Short real released-RTL check

```sh
python3 tools/public_verify.py smoke --out build/smoke --limit 2
```

The command writes `build/smoke/released-input.json`, a list of exact product
files and their SHA-256 digests. Later checks refuse files whose bytes no longer
match that list. Chisel's `BlackBox` only instantiates the distributed
SystemVerilog (SV): it adds no clocked adapter state and does not reimplement
the codec. Two encoder and two
decoder full files use deterministic mathematical pixels and the fixed C
reference. The decoder negative run must fail with
`PNG_DECODER_BYTE_MISMATCH`, not an arbitrary build/runtime failure. C++ only
clocks/resets/triggers; it does not substitute pixels. `qualification-compile.json`
records actual sources and hashes before/after compilation. Released optional
product assertion layers are not distributed; Chisel testbench assertions are
compiled. A negative control deliberately changes an expected answer and
requires the checker to detect that change; compilation failure alone cannot
pass it. Product assertion-bind layers are optional extra checking modules,
not the codec implementation. No unknown external image corpus is used.

This is a small smoke, not the original 15-format complete regression.
`--limit 150` expands the encoder's complete 150 base combinations and the
decoder's first 150 cases; it does **not** mean 228 decoder cases. For full
152 encoder / 228 decoder base files and separate protocol tests use the
explicit file-list-locked flow below. Additional alpha and boundary options
select their own test sets rather than increasing that base count implicitly.

## Fresh generation from original Chisel source

```sh
python3 tools/public_verify.py emit --out build/regenerated
```

This generates both independent product tops with the pinned toolchain, compares
each original SV body against the released body by exact bytes, reapplies the
format-safe declaration, and requires exact released bytes again. Existing
output is refused. Optional generated assertion-layer files are tool output,
not additional released RTL files. Here the header is the added copyright/license
comment, not a PNG file header. Insertion is idempotent (applying it twice changes
nothing) and never removes generator notices. A body difference is a failure to investigate, not
permission to replace the released RTL.

## Extended functional and performance entrypoints

The smoke writes `build/smoke/released-input.json`; after running smoke once,
use it to qualify the following additional tests against the same independent SV:

```sh
export OPENIMAGE_PNG_EXTERNAL="$PWD/build/smoke/released-input.json"
export OPENIMAGE_PNG_EXTERNAL_SHA256="$(python3 -c 'import hashlib;from pathlib import Path;print(hashlib.sha256(Path("build/smoke/released-input.json").read_bytes()).hexdigest())')"
python3 tools/verify_encoder.py --out build/full-encoder --jobs 1
python3 tools/verify_decoder.py --out build/full-decoder --jobs 1
python3 tools/verify_encoder_protocol.py --out build/encoder-protocol --jobs 1
python3 tools/verify_decoder_edges.py --out build/decoder-protocol --source build/full-decoder --jobs 1
```

Check each entrypoint's `--help` for extra alpha/boundary/reset/control options;
maximum-image tests are explicit and costly, not implicit in smoke:

```sh
python3 tools/verify_large.py --out build/large-encode --direction encode --jobs 1
python3 tools/verify_large.py --out build/large-decode --direction decode --jobs 1
python3 tools/verify_performance.py --out build/perf-encode --direction encode --main-matrix --product-base HEAD --jobs 1
python3 tools/verify_performance.py --out build/perf-decode --direction decode --main-matrix --product-base HEAD --jobs 1
```

Full normal performance is 170 encoder / 255 decoder configurations, each with
one separately measured cold-start task and 32 continuous tasks on one instance.
A task is one complete image. Raw bytes exclude PNG framing, row-filter bytes
and separate metadata; events/CSV distinguish them from file bytes and pixels.
Let B be raw image bytes and L be the command-handshake to completion-handshake
cycle difference (no +1), and c1/c32 are completion-handshake cycles for the
first/last of the 32 continuous tasks. II=(c32-c1)/31 and sustained raw
throughput is B/II, using **31** actual gaps, not 32. Each normal case must
satisfy `20*B>=19*L` and `20*31*B>=19*(c32-c1)`, plus its first-image timing
budget. These are project test targets, not PNG requirements.
Encoder first-image timing locates the emitted byte needed to reconstruct the
first image sample (not a filter-type byte); decoder timing uses its first raw
sample beat. The precise startup budgets and test-set distinctions are in
[Results and limitations](results.md#measurement-definitions).
For six decoder pressure tests use `--supplement pressure` instead of
`--main-matrix` on the decoder performance command, with a new output directory.
The sixth test, zero-based case 5 (`idat-single-byte`), puts one compressed byte
in each IDAT chunk. It is known to miss the report-only 0.89 raw bytes/cycle
suggestion. That numerical miss makes the Python command exit 1 even though
normal RTL simulation succeeds. Identify this known result by checking
`completion.json`: `status` must be 1 and
`performance_contract.failed_contract_cases` must equal `[5]`.
Also require `simulation-exit.json.returncode` to be 0 and `negative-exit.json`
to record a nonzero return code and designated assertion `PNG_PERF_TAG_NEGATIVE`
(an intentionally wrong task tag). Missing output files, arbitrary compile
failure or a different failed-case set are not that expected numerical miss.
Never mask all failures with `|| true`.

Extended entrypoints are supplied for users to rerun; publication smoke does
not newly rerun all of them, quality baselines or restricted synthesis. Full
original logs/operations are not bundled. On default-PIE C environments, an
explicit `CFLAGS=-no-pie` may be needed by the original reference paths.
