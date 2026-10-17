<!-- OpenRTL file declaration
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: png-codec
Repository introduction: Standalone lossless PNG encoder and decoder.
File: README.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# PNG Codec Hardware Accelerator

A standalone PNG lossless encoder and decoder, written in Chisel with generated SystemVerilog. Use it to convert raw image rows to complete PNG files and back in image acquisition, storage and transmission pipelines, through native streaming interfaces for ASIC or FPGA integration.

[简体中文](README.zh-CN.md)

Released under the [MIT License](LICENSE), including commercial use, modification and redistribution with the required notices. Dependencies retain their own licenses; see [Third-party notices](THIRD_PARTY_NOTICES.md).

[Repository contents](#repository-contents) · [Applications](#applications) · [Performance metrics](#performance-metrics) · [Implemented protocols](#implemented-protocols) · [Quick reproduction](#quick-reproduction) · [Verification scope and limitations](#verification-scope-and-limitations) · [Synthesis results](#synthesis-results) · [Publication scope](#publication-scope) · [OpenRTL and custom integration](#openrtl-and-custom-integration)

## Repository contents

- `src/main/` and `build.mill`: Chisel product source and pinned generator configuration.
- `rtl/encode/rtl.f` and `rtl/decode/rtl.f`: independent product file lists, with 15 encoder and 24 decoder SystemVerilog files. Compile the two lists separately.
- `src/tool/`, `reference/`, `sim/` and `tools/`: test environments, fixed software-reference adapters, clock drivers and generation/verification commands.
- [Architecture](docs/architecture.md), [Native interface](docs/interface.md), [Reproduction](docs/reproduction.md), [Results and limitations](docs/results.md) and [Synthesis results](docs/synthesis-summary.md): implementation, integration, reproduction and measured results.

## Applications

Use it wherever a system needs lossless conversion between raw sample rows and PNG files within the supported profile below. Examples include recording captured images, storing or retrieving embedded image assets, and encoding or decoding images for transport. These are integration examples, not restrictions on use or measured system-level guarantees. The hardware preserves the selected sample format, including 16-bit and indexed data, rather than expanding everything to RGBA. Host control, DMA and system buffering are external.

## Performance metrics

For the slowest sustained cases in the normal test set, each with 65,536 raw image bytes, command-to-completion latency / sustained task spacing were 66,876 / 66,877 cycles for encoding and 68,230 / 68,231 cycles for decoding. Spacing uses 32 identical images on one instance. At an assumed 500 MHz, latency converts to 133.752 / 136.460 µs, not board or silicon measurements.

All 425 normal configurations met the project's 0.95 raw-byte/cycle target. **The separate one-byte-per-IDAT stress case reached only 0.062090534168 raw bytes/cycle, below its 0.89 reporting target.** Workloads, first-image latency, exact formulas and stress results are in [Results and limitations](docs/results.md).

## Implemented protocols

The hardware implements static, non-interlaced PNG: complete file framing, CRC, row filtering, zlib and DEFLATE. It supports all 15 legal PNG colour/depth combinations, width and height 1–8192, raw rows up to 65,536 bytes and combined PLTE/tRNS metadata up to 1,024 bytes. Encoding uses a selected fixed row filter and STORED or FIXED blocks; decoding also supports DYNAMIC and mixed blocks.

STORED means uncompressed blocks, FIXED uses fixed Huffman tables, and DYNAMIC transmits its tables. The native interface uses 64-bit valid/ready streams, synchronous reset and backpressure/error recovery. Output is provisional until successful completion. Byte order, counters and memory semantics are in [Native interface](docs/interface.md); datapaths are in [Architecture](docs/architecture.md).

## Quick reproduction

Clone the repository and install the versions listed in [Reproduction](docs/reproduction.md), including Python 3.12, JDK 21, Mill 1.1.7 and Verilator 5.050. From the repository root:

```sh
python3 tools/check_publication.py
python3 tools/test_source_headers.py
python3 tools/public_verify.py lint
python3 tools/prepare_reference.py --jobs 1
python3 tools/public_verify.py smoke --out build/smoke --limit 2
```

Expected markers: `PNG_PUBLIC_HEADERS_PASS`, `PNG_RELEASED_LINT_PASS`, `PNG_RELEASED_SMOKE_PASS`. Smoke checks two files in each direction against fixed software, plus a deliberately wrong decoder byte. Use fresh output directories. Regeneration, larger regressions and performance commands: [Reproduction](docs/reproduction.md).

## Verification scope and limitations

The recorded functional regression covers 152 encoder and 228 decoder files, plus protocol errors, backpressure, reset/recovery and full 8192×8192 1-bit grayscale images. This does not cover every maximum-size format/texture. Functional tests, timed tasks and offline file checks are distinct sets; their counts and software-reference edge cases are explained in [Results and limitations](docs/results.md).

Adam7, APNG, dynamic-Huffman encoding, automatic filter selection, quantization, colour management, GZIP/ZIP and preset dictionaries are outside scope. The portable-command checks used preinstalled tools and a warm public Maven cache, not a demonstrated empty-host or offline bootstrap; actual runs and limits are in [Publication checks](docs/publication-checks.md).

## Synthesis results

Both complete tops passed setup in Design Compiler NXT on TSMC 28 nm HPC+, SSG 0.81 V / −40 °C, at 500 MHz with 10% setup uncertainty. Of the reported worst setup paths, 100% for the encoder and 99.8713% for the decoder have fewer than 50 levels counted by Z/ZN output pins. This is target-specific synthesis, not place-and-route signoff or board validation. Exact conditions, counting rules, slack, area and SRAM resources are in [Synthesis results](docs/synthesis-summary.md).

## Publication scope

Included: original Chisel, generated RTL, generation/simulation commands, bilingual documentation and synthesis summary. Excluded under licensing/confidentiality restrictions: technology/SRAM libraries, commercial EDA, original synthesis scripts/constraints/reports, mapped netlists and snapshots. No SoC, DMA engine or board project is supplied.

## OpenRTL and custom integration

[OpenRTL](https://github.com/OpenRTL-Lab) develops reusable ASIC/FPGA accelerators. For codec customization, memory/bus integration or performance/resource optimization, contact us through the organization profile or [OpenRTL website](https://openrtl.top/). Please provide image formats, target technology, interfaces and performance/resource goals.

Customization is optional: MIT commercial use does not require purchasing services. Do not post confidential specifications in public issues.
