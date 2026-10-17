<!-- OpenRTL file declaration
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: png-codec
Repository introduction: Standalone lossless PNG encoder and decoder.
File: THIRD_PARTY_NOTICES.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# Licensing and dependency provenance

This document separates the code covered by this repository's MIT license from
external tools and libraries governed by their own licenses. Versions describe
the documented verification environment, not software bundled into hardware.

[简体中文](THIRD_PARTY_NOTICES.zh-CN.md)

Original hardware, verification, reference adapters and publication utilities
are MIT-licensed under [MIT License](LICENSE), copyright 2026 OpenImage contributors.
Branding does not transfer the original copyright. Test images are generated
by original mathematical formulas; no outside image corpus is redistributed.
Generated SV retains CIRCT's generator notice and the original hardware body.
No third-party implementation is re-licensed MIT merely because it is used
to compile or check this project.

| External dependency | Version / license | Official acquisition |
| --- | --- | --- |
| libpng | 1.6.59 / PNG Reference Library License v2 | [tag archive](https://codeload.github.com/pnggroup/libpng/tar.gz/refs/tags/v1.6.59) |
| zlib | 1.3.2 / zlib License | [archive](https://zlib.net/zlib-1.3.2.tar.gz) |
| Mill | 1.1.7 / MIT | [Mill](https://mill-build.org/) |
| Scala | 2.13.18 / Apache-2.0 | [Scala](https://www.scala-lang.org/) |
| Chisel | 7.13.0 / Apache-2.0 | [Chisel](https://github.com/chipsalliance/chisel) |
| CIRCT firtool | 1.149.0 / Apache-2.0 with LLVM exception | [CIRCT](https://github.com/llvm/circt) |
| Verilator | 5.050 / LGPL-3.0 or Artistic-2.0 | [Verilator](https://verilator.org/) |
| JDK | 21 / distribution-specific, commonly GPLv2 + Classpath exception | [OpenJDK](https://jdk.java.net/) |
| Python | 3.12 / PSF License | [Python](https://www.python.org/) |
| GCC / CMake / Make | host tools, separate licenses | official distributions |

Only unmodified [libpng license](licenses/libpng-LICENSE) and
[zlib license](licenses/zlib-LICENSE) texts are included. Dependency implementations,
build binaries, compilers, commercial EDA, PDK/SRAM libraries and mapped
netlists are not bundled. `tools/prepare_reference.py` downloads and verifies:

```text
libpng-1.6.59.tar.gz SHA256 2540302a1844ad2b2b501977abecfa850f265f97b78f065a712ab4074a89f5b5
zlib-1.3.2.tar.gz SHA256 bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16
```

`SOURCE_MANIFEST.json` records original, generated, adapted/new utility and legal
text classifications. Strict-format VERSION, JSON, `rtl.f` and `.gitignore`
receive no inserted comment header and have explicit `.license` companions;
the companion identifies the associated file's license without changing its
syntax. Documentation revisions update corresponding manifest digests; this
is not a claim that a mutable JSON manifest never changes. Legal license texts
are never prefixed with an original-code claim. The manifest's own integrity
is the committed Git blob; it cannot contain its own SHA-256. Every other
published file has an explicit digest in that manifest. `published_sha256`
identifies the whole distributed file; `body_sha256` identifies the bytes after
removing exactly the added declaration comment (or the whole file if there is
no inline declaration). Original-source digests identify provenance separately;
adapted files are classified explicitly rather than called unchanged originals.
Testbench memory initialization supplies test vectors only and is not part of
the encoder/decoder product file lists used for hardware integration.
