<!-- OpenRTL file declaration
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: png-codec
Repository introduction: Standalone lossless PNG encoder and decoder.
File: publication-checks.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# Publication checks

The following **new portable commands actually ran successfully** on Linux on
2026-10-17 for hardware version 1.2.1. This is a bounded command check,
separate from the original complete version qualification in [Results and limitations](results.md).

[简体中文](publication-checks.zh-CN.md)

SV means SystemVerilog. A file body is its content after removing exactly the
added license/organization comment; SHA-256 digests identify both body and
distributed bytes. A negative control deliberately corrupts a file or expected
answer and must be rejected by the intended checker, not by an unrelated error.
Idempotency means applying the declaration twice leaves the file unchanged.

| Actual command | Observed result |
| --- | --- |
| `python3 tools/check_publication.py` | Complete 141-file inventory; 39 released SV; source/declaration hashes checked |
| `python3 tools/test_source_headers.py` | Exact-body round trip, idempotency, missing/duplicate/tampered header, changed-body and extra-file controls passed |
| `python3 tools/public_verify.py lint` | Both independent released lists passed Verilator 5.050 with assertions enabled |
| `python3 tools/prepare_reference.py --jobs 1` | Pinned public archives downloaded and SHA-checked; static references built successfully |
| `python3 tools/public_verify.py emit --out build/regenerated-r1` | Both tops regenerated; all 39 SV bodies exactly equal released original bodies; re-applied declarations exactly equal published bytes |
| `python3 tools/public_verify.py smoke --out build/smoke-r1 --limit 2` | Two encoder and two decoder complete files passed, plus genuine specified decoder-byte negative control |

Smoke used **Gray1 (1-bit grayscale)**, width 17, encoder height 3 / decoder height 5, filter None,
STORED and FIXED. Encoder files were 106 and 101 bytes, with one genuine LZ77
match in the FIXED case. Encoder output was checked by the pinned libpng/zlib
reference; decoder input was generated offline and exact raw bytes compared.
The negative simulation exited 1 and specifically asserted
`PNG_DECODER_BYTE_MISMATCH`; an arbitrary compilation failure is not accepted.
It is not a new test of all 15 formats, all error cases or maximum images.

The testbench compiled the independent original released SV directly via
Chisel BlackBoxes (external-module instances, not replacement codec logic),
with hashes recorded before/after compilation and testbench
assertions enabled. Optional product assertion-bind layers are not bundled.
These optional bind layers are extra assertion modules, not product datapaths.
The adapter report's `product_assertion_binds_included` flag is false to say so; no
hardware body, mathematical vector or checking algorithm was changed.

The declared generator versions are pinned in build.mill and reproduction.md.
The run used **preinstalled tools and a warm cache of public Maven artifacts**;
it does **not** prove installation on a blank machine, an offline bootstrap or
compatibility with other compiler versions. Public source-archive downloading
was independently exercised, but first-time JVM artifact downloading was not.
Source generation reported six existing Chisel deprecation warnings, not a
zero-warning claim. Inherited generated-SV end-of-file blank lines remain
unchanged rather than being trimmed for formatting.

After these runs, only publication documentation and its manifest hashes were
finalized; later reader-documentation revisions also leave tested code and RTL
unchanged. The main branch and version tag `v1.2.1` identify the same
self-contained release snapshot: one root commit, without internal development
history.
The documentation snapshot also includes a dedicated synthesis summary, so its
inventory first became 142 files. Separating all eight language pairs into
individual English and Simplified Chinese documents brings the current
inventory to 150 files, including 16 Markdown files. Inventory/header checks
cover this snapshot; the earlier 141-file count describes the original command
run. The additions are documentation, not new RTL or another hardware test.
Normal original function,
performance and target-specific synthesis results remain historical version
evidence: this publication did not rerun the full matrix, restricted synthesis,
place-and-route, board or silicon tests. The fragmented-IDAT pressure gap is
still disclosed in README and results.md.
