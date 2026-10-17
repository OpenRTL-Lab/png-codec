<!-- OpenRTL file declaration
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: png-codec
Repository introduction: Standalone lossless PNG encoder and decoder.
File: results.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# Versioned results and limitations

This document reports what was measured for hardware version 1.2.1, how to
interpret the numbers and which conclusions they do not support. Historical
hardware results and the later portable-command checks are separate evidence.

[简体中文](results.zh-CN.md)

The original source/RTL provenance is fixed in [Source manifest](../SOURCE_MANIFEST.json).
Publication adds headers and portable verification glue, not a new hardware
algorithm. Exact header removal recovers all original product, testbench,
reference and wrapper bodies. A single adapted qualification-report flag and
new public tools are explicitly classified rather than called header-only.
That flag records whether optional product assertion-binding modules were
compiled; this distribution omits those modules but compiles testbench
assertions. It changes the report, not the codec or expected image data.

## Measurement definitions

Here E means encoder and D means decoder. Functional regression, performance
measurement and offline file checking are separate test sets; their counts
must not be added as if they were distinct hardware test images. A task is one
complete image, from command handshake to completion handshake. Every channel
handshake occurs at a clock edge with valid and ready both high.

For width W, height H, channel count C and depth d, raw row bytes are
`R = ceil(W*C*d/8)` and image bytes are `B = R*H`. These include a row's last
partially used byte but exclude filter-type bytes, PNG framing and metadata.
The original measurement script uses cycle differences, with no additional +1:

- Cold-start full latency: `L = c - a`, where a is command acceptance and c is
  completion acceptance for a separately measured cold-start task.
- Continuous interval: `II = (c32 - c1)/31`, where c1 through c32 are completion
  handshakes for 32 identical tasks on one instance, without reset or artificial
  idle between them. This is completion spacing, not a pipeline issue rate.
- Sustained raw throughput: `T = 31*B/(c32-c1) = B/II`. The tables use this
  rate, not B/L or the 32-image batch rate. No parallel-instance rates are added.

The normal set's project targets, not PNG requirements, are `B/L >= 19/20`
and `B/II >= 19/20`, i.e. at least 0.95 raw bytes/cycle. Exact checks are
`20*B >= 19*L` and `20*31*B >= 19*(c32-c1)`. Rounding the allowable cycle
count upward would incorrectly accept boundary failures.

First-image latency F is the cycle difference from command acceptance to the
first image-bearing output handshake. E parses the actual emitted PNG to find
the last compressed byte necessary to reconstruct the first image-sample byte,
excluding the row's filter-type byte, and times the beat containing that byte.
D times the first raw-sample output beat. Neither uses a file-header beat.
Let M be the combined PLTE/tRNS length. For every cold and continuous normal task,
the project checks `F <= M + R + 64` for E; for D it checks `F <= M + K`, with
K=128, 256 or 4096 cycles for the test's STORED, FIXED or DYNAMIC stream mode,
respectively. These are configuration-dependent startup budgets, separate from
throughput; in these test formulas each byte in M and R contributes one cycle
of allowance. The six decoder stress cases instead report a suggested 0.89 raw
bytes/cycle target; they do not replace the normal thresholds.

## Functional tests

A negative control deliberately corrupts an expected value or test input and
must make its checker reject the result; it tests error detection, not another
successful image decode.

Original functional regression: 152 E / 228 D full PNG files, 37 E / 66 D
protocol/error recovery tasks, reset/backpressure/output bounds, specific
pixel/metadata/status/tag controls, alpha/boundary cases and full 8192² Gray1
both ways (Gray1 means 1-bit grayscale). The maximum-size test is not all
formats/contents at max size.
418 RGBA16 (four channels, 16 bits per channel) checks consume actual files; these are offline semantic checks,
not 418 new hardware tasks. RFC boundary tests declaring 31/32 distance-code
entries (HDIST+1) are not claimed as software interoperability when the fixed
zlib rejects them; reserved distance symbols are still invalid. Reference:
libpng 1.6.59, zlib 1.3.2 (one underlying DEFLATE software implementation).

## Normal performance and pressure tests

Normal main matrix: 170 E / 255 D configurations; `(170+255)*33 = 14,025`
tasks total (one cold-start plus 32 continuous tasks per configuration).
Every normal item passes exact 19/20 raw B/c thresholds and first-data budget.
The comparison below selects the lowest sustained raw throughput in each
direction, not the same input image for both. L, II and F are in cycles;
the throughput column is B/II, in raw bytes/cycle. Inputs and outputs are
supplied/accepted as soon as the interface permits; external stalls are not
included. These are complete codec tasks, not isolated compressor rates.

| Direction | Image / format / content | Filter / block mode | B, raw bytes | L | II | B/II | F / budget |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| Encoder | 1024×512, indexed 1-bit, gradient | None / STORED | 65,536 | 66,876 | 66,877 | 0.979948263230 | 92 / 199 |
| Decoder | 256×256, indexed 8-bit, gradient | None / DYNAMIC | 65,536 | 68,230 | 68,231 | 0.960501824684 | 1,774 / 5,119 |

In the full-metadata encoder tests, first-image latency F was 1,235 cycles
in STORED mode and 1,232 in FIXED mode, both below their 1,343-cycle budget.
No ceiling-cycle rounding or
parallel-instance rate summation. L and interval have distinct endpoints;
the 32-task interval uses 31 actual gaps.

Six pressure cases each use 65,536 raw bytes:

| Pressure | L | II | Raw B/c | 0.89 suggestion |
| --- | ---: | ---: | ---: | --- |
| 15-bit literal code | 66817 | 66818 | 0.980813553234 | pass |
| length-3 match train | 65874 | 65875 | 0.994853889943 | pass |
| distance-1 overlap | 65866 | 65867 | 0.994974721788 | pass |
| distance-32768 wrap | 65866 | 65867 | 0.994974721788 | pass |
| STORED/FIXED mix | 65870 | 65871 | 0.994914302197 | pass |
| one byte per IDAT | 1055490 | 1055491 | 0.062090534168 | not met, report-only |

The fragmented file is 857,239 bytes. Even an ideal 8-byte input needs at least
107,155 cycles, an upper bound of 0.611600018665 raw B/c; actual parsing adds
substantial cost. The failure is not excused by calling it IO-bound; the normal
contract is not weakened. The historical size comparison used the same fixed
18-input subset for the previous benchmark and this implementation: 1,222,468
bytes here versus 1,215,631 baseline (+6,837). Each file stayed within 120% of
its baseline size, and the total within 105%, with raw/metadata identity and
deliberate size-error controls checked. This is not a comparison over all 170
encoder configurations; earlier benchmark files are not bundled, so public
commands alone do not reproduce that historical comparison.

## Target-specific synthesis

The full condition table, disjoint depth bins, area breakdown and mapped SRAM
counts are in [Synthesis results](synthesis-summary.md). The summary
below retains the result and measurement definition for this results document.

Here WNS is worst setup slack and TNS is total negative setup slack. Z/ZN is
a project-specific count: take one worst setup data path per endpoint and
count only data-path output pins whose leaf names are exactly Z or ZN, not
clock-tree pins. The ratio's numerator is endpoints whose count is strictly
greater than 50; the denominator is all endpoints in the census. It is not
the count of all cell types, maximum RTL depth or all possible timing paths.
Independent target-specific synthesis: 500 MHz, 2 ns, setup uncertainty0.2 ns,
maximum I/O delay1 ns, SSG0.81 V at−40°C. Encoder WNS+0.000149965 ns, TNS0,
negative setup endpoints0, strict data Z/ZN>50 count0/12138. Decoder
WNS+0.000034332 ns, TNS0, negative endpoints0, strict>50 count36/30295
(0.118831490345%). Both exact gates pass WNS>=0 and proportion<=5%.
The largest decoder Z/ZN count was 58; the gate limits the proportion of
endpoints above 50, not the maximum individual count. Hold is not evaluated for
acceptance here. Synchronous memory enable/latency and same-address behavior
were part of that target contract. Restricted reports, vendor netlists,
physical SRAM models and commercial tools are excluded from this repository.
No P&R signoff, FPGA board, silicon, physical die area/power, portable Fmax or verified
same-condition commercial-IP performance is claimed.

The publication verification results are separately recorded in
[Publication checks](publication-checks.md). Full internal operation histories
are intentionally not supplied as dependencies of public commands.
