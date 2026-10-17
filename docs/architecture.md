<!-- OpenRTL file declaration
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: png-codec
Repository introduction: Standalone lossless PNG encoder and decoder.
File: architecture.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# Architecture and memory

The released source is the original PNG 1.2.1 implementation. `build.mill`
pins the generator versions. Source-to-release mapping and per-file body
digests are in [Source manifest](../SOURCE_MANIFEST.json). The original
source commit is a provenance identifier, not an additional reachable Git
history in this publication repository.

[简体中文](architecture.zh-CN.md)

## Encoder data path

The encoder accepts raw sample bytes and optional PLTE/tRNS bytes and produces
a complete PNG file. A transfer, or handshake, is a clock edge on which a
channel's `valid` and `ready` are both high. Registered input checking holds
each accepted 64-bit beat while validating its byte mask and end marker.

The path is input checking → byte frontend → synchronous row filter → row
queue → `WideZlibEncoder` → IDAT framing and CRC → output byte packer → output
queue. The row queue stores pending bytes so a temporary downstream stall
does not require every upstream stage to stop on the same edge. The compressor
combines bounded LZ77 match search with a bit buffer: encoded bits accumulate
there until enough are available to form an output byte.

IDAT framing uses two buffers, each holding 2,048 bytes; these are the two
storage banks in the RTL, not external memory banks. The packer forms 64-bit
stream beats. The final queue holds two beats and does not bypass a newly
arriving beat directly to its output (`flow=false`). Queue space may be
reserved internally before a transfer, but externally reported byte counts
advance only at their specified actual handshake.

Metadata input is also registered and validated, with bytes read ahead for
later consumption. Initialization splits arithmetic into registered partial
results without changing the native command or stream format; see the
[Native interface](interface.md).

## Decoder data path

The decoder accepts a complete PNG file and outputs its image descriptor,
PLTE/tRNS bytes and raw sample bytes on separate channels. Registered input
feeds a 16-byte sliding file buffer, followed by PNG parsing and CRC checking.
`FastInflate` uses a 64-bit compressed-bit buffer, canonical Huffman lookup
tables and a 32 KiB LZ77 history. Inverse row filtering reconstructs samples;
a row queue and byte packer feed the external output queue.

An IDAT chunk boundary does not end the zlib stream: its bit buffer, history
and Adler checksum remain active across chunks. Each chunk CRC and the exact
expected count of filtered image bytes are checked before final completion.
The decompressor can inspect a following match while copying the current one,
but starts that next copy only at the current copy boundary. For overlapping
copies at distance 1 or 2, forwarding supplies newly produced bytes instead of
reading history locations that have not yet been written.

## Memory and integration contract

The product includes synchronous read memories with explicit cycle latency,
read/write enables, address bounds and same-address forwarding rules encoded
in RTL. Some bounded Huffman symbol lookup paths are intentionally asynchronous
combinational logic; do not replace all memories with one assumed macro model.
Integrators must preserve the semantics of **each** generated memory, including
read-during-write behavior. Do not turn a one-cycle read into zero-cycle data or
silently change enables to meet timing. Physical macros and their libraries
are not included. Reset cancels public validity and context, not necessarily
every RAM bit; unwritten data must not become a valid answer.

There is no external-memory controller, AXI/DMA wrapper, board primitive or
full-image buffer. The implementation is byte-oriented internally despite
64-bit external streams. Width does not imply 8 raw bytes/cycle compute rate.
The encoder and decoder RTL units share some module names but not necessarily
their bodies, so compile the two relative file lists separately. This document
describes structure, not a throughput guarantee; measured end-to-end rates and
their workload definitions are in [Results and limitations](results.md).
