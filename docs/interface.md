<!-- OpenRTL file declaration
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: png-codec
Repository introduction: Standalone lossless PNG encoder and decoder.
File: interface.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# Native PNG interface

This document specifies how an RTL integrator submits one complete image and
determines whether its output may be used. The two tops are `PngEncoder` and
`PngDecoder`; they are independent, not a combined streaming pipeline.
The authoritative field definitions are [Protocol.scala](../src/main/scala/openimage/png/Protocol.scala).
Both independent tops use `clock` and synchronous active-high `reset`.
All channels transfer only on `valid && ready`; while stalled, all payload
bits remain stable. `cmd` begins one task; `done` must be accepted before the
next command. Reset alone cancels a task without a completion.

[简体中文](interface.zh-CN.md)

At the file level, the bounded static, non-interlaced profile follows the
[PNG specification](https://www.w3.org/TR/png/), with the
[zlib format](https://www.rfc-editor.org/rfc/rfc1950) wrapping the
[DEFLATE format](https://www.rfc-editor.org/rfc/rfc1951).
STORED blocks carry uncompressed bytes; FIXED and DYNAMIC blocks use fixed
and transmitted Huffman tables, respectively. The encoder supports STORED
or FIXED; the decoder also handles DYNAMIC and mixed blocks. These are
file-format modes, not different bus protocols or generator variants.

| Command field | Width / meaning |
| --- | --- |
| tag | 32 bits, copied to descriptor/completion |
| inputBytes, outputLimit | 32 bits, legal values 1..2^30 |
| imageWidth, imageHeight | 14 bits, legal dimensions 1..8192 |
| colorType, bitDepth | 3 / 5 bits; supported combinations listed below |
| filter, fixed | 3 bits (0..4); 1 bit (0 STORED, 1 FIXED) |
| paletteBytes, transparencyBytes | 10 / 9 bits; combined metadata <=1024 |

The supported PNG colour type / bit-depth combinations are grayscale (type 0)
at 1/2/4/8/16 bits, RGB (2) at 8/16, indexed (3) at 1/2/4/8, grayscale-alpha
(4) at 8/16 and RGBA (6) at 8/16: 15 combinations. Dimensions are bounded as
above, with at most 65,536 raw bytes per row. `filter` selects PNG filter
None/Sub/Up/Average/Paeth for values 0/1/2/3/4. `fixed` selects the encoder's
DEFLATE block mode, not a generator configuration.

The decoder uses tag/inputBytes/outputLimit; format fields are parsed from IHDR.
For the encoder, inputBytes counts raw image bytes only, and outputLimit bounds
the complete PNG file. For the decoder, inputBytes counts the complete PNG file
(including every metadata chunk), and outputLimit bounds raw image output.
Separate metadata-channel bytes are not added to raw image byte counts.

`StreamBeat` consists of `data` (64 bits), `keep` (8 bits) and `last` (1 bit).
Each keep bit marks its corresponding byte lane as valid; byte zero is data[7:0]. Non-final keep is 0xff,
final keep is a nonzero low-contiguous mask. No empty beats. `last` ends the
whole file/raw image or metadata sequence, never a row. Metadata is PLTE then
tRNS on the separate metadata channel. For encoding, their declared lengths
are paletteBytes and transparencyBytes and are not included in raw inputBytes.
For decoding, their chunks are part of inputBytes but their separate output
channel is not part of raw outputBytes. No metadata means no
metadata beat. Descriptor (tag/width/height/type/depth/two lengths) precedes
metadata and raw pixels on the decoder.

Raw row size R=ceil(width*channels*bitDepth/8), image bytes=R*height. Rows are
adjacent with no additional stride padding; the final byte can still have
unused low bits. A 17-pixel 1-bit grayscale row occupies 3 bytes, with 7 unused
low bits in byte 3. Lane order is separate from sample byte order: a 16-bit
sample 0x1234 sends 0x12 in the earlier stream lane, followed by 0x34.
16-bit samples are most-significant byte first; sub-byte samples are high-bit
first. Encoder unused low row-tail bits must be zero; decoder clears these on
external output but keeps original reconstructed bytes internally for the next
row prediction. Indexed samples must fit the actual PLTE. Grayscale/RGB tRNS
keys must fit their bit depth; optional suggested palettes for RGB/RGBA remain
metadata. No palette expansion or colour-space conversion is done by hardware.

The completion reports tag, a 3-bit status, actual inputBytes/outputBytes and
pixels; the decoder also reports its parsed format through the descriptor.
Raw-image counters exclude the separate metadata bytes; PNG-file counters
include all chunks. Status: 0 OK; 1 BAD_COMMAND; 2 UNSUPPORTED_PROFILE;
3 MALFORMED_STREAM; 4 CHECKSUM_ERROR; 5 INPUT_LENGTH_ERROR; 6 OUTPUT_LIMIT.
First detected error wins. Invalid commands accept no input. Previously visible
valid output/descriptor/metadata remains until accepted; normal errors do not
retract it. Drain the current task's declared or early-last boundary before
done, never consume the following task. Output is provisional until final OK;
the receiver must discard it on error. Byte limits are reserved before exposing
output, so no bytes above outputLimit are delivered. Completed errors allow
the next command without reset. Reset cancels validity/context and permits a
new command; it does not fabricate a success or cancelled done.
