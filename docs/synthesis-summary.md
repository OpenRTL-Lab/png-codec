<!-- OpenRTL file declaration
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: png-codec
Repository introduction: Standalone lossless PNG encoder and decoder.
File: synthesis-summary.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# Synthesis results

PNG 1.2.1's complete encoder and decoder passed setup at 500 MHz in Design
Compiler NXT using TSMC 28 nm HPC+, SSG 0.81 V / −40 °C and 10% setup clock
uncertainty. Worst setup slack is +0.000149965 ns for encoding and
+0.000034332 ns for decoding; both have zero total negative setup slack and
zero violating endpoints. Under the Z/ZN output-pin counting rule defined
below, 100% and 99.8713% of their reported paths, respectively, have fewer
than 50 levels.

[简体中文](synthesis-summary.zh-CN.md)

## Design and conditions

| Item | Condition |
| --- | --- |
| Product | PNG Codec 1.2.1; encoder and decoder synthesized independently |
| Complete tops | `PngEncoder`, `PngDecoder`; no verification environment |
| Software | Synopsys Design Compiler NXT X-2025.06-SP3 |
| Standard cells | TSMC 28 nm HPC+, 7-track |
| Library corner | SSG, 0.81 V, −40 °C |
| Clock | 2.000 ns period, 500 MHz |
| Setup uncertainty | 0.200 ns, 10% of period |
| Maximum input / output delay | 1.000 ns each |
| Output load | 0.020 in the target library's capacitance units |
| Maximum data transition / fanout | 0.250 ns / 32 |
| Storage mapping | Synchronous one-read/one-write SRAM; 68 encoder / 96 decoder physical macros |
| Area basis | Synthesized cell area including mapped SRAM, not die area |

These are the archived run's conditions, not guaranteed operating points for
other libraries. Standard cells and SRAM use the selected SSG views. The
output-load value is reported in library units, without asserting an
independently verified conversion to pF. Clock uncertainty reserves setup
margin; it does not change the codec protocol.

Public RTL represents generic memories. Integration must preserve each
memory's read latency, enables and simultaneous-read/write behavior, rather
than replacing every array with an assumed macro. Some small lookup tables
remain logic. See [Architecture](architecture.md).

## Setup and logic depth

WNS is the minimum setup slack; TNS sums negative setup slacks. For the depth
statistics, select one worst max-data path for each constrained endpoint.
Count only data-path output pins whose leaf name is exactly `Z` or `ZN`, one
per pin. Nets, textual report rows, clock-to-Q and clock-tree points do not
count. This is a library-dependent statistic, not a universal gate depth.

| Metric | Encoder | Decoder |
| --- | ---: | ---: |
| Setup | Pass | Pass |
| WNS, ns | +0.000149965 | +0.000034332 |
| TNS, ns | 0 | 0 |
| Violating setup endpoints | 0 | 0 |
| Constrained, reported endpoints | 12,138 | 30,295 |
| Levels strictly <50 | 12,138 (100%) | 30,256 (99.8713%) |
| Levels exactly 50 | 0 | 3 |
| Levels strictly >50 | 0 (0%) | 36 (0.1188%) |
| Maximum level count | 44, one path | 58, one path |

The three depth bins are disjoint. Paths include internal-to-internal,
internal-to-output and input-to-internal, with counts 11,227 / 260 / 651 for
encoding and 29,324 / 420 / 551 for decoding. SRAM endpoints are distinguished
by complete pin names. The archived audit matched exact Z/ZN pin sequences
between full reports and exported timing points; it did not merely compare
rounded slack strings.

The project gates are WNS ≥0 and at most 5% of endpoints having **strictly
more than 50** counted levels. The exact check is `20*N_above50 <= N_total`.
Exactly 50 is not above 50. The decoder's maximum of 58 therefore does not
fail this proportion-based gate. These results do not establish hold or
recovery/removal closure, or enumerate every possible path.

## Area and resources

The categories below do not overlap. The tool's original combinational area
includes buffers/inverters; subtract them before displaying the first row.
Values are rounded to three decimals and percentages to four; the reported
total is not recomputed from rounded rows.

| Category | Encoder, µm² | Share | Decoder, µm² | Share |
| --- | ---: | ---: | ---: | ---: |
| Combinational, excluding buffers/inverters | 13,068.104 | 2.2125% | 37,203.936 | 4.2500% |
| Buffers/inverters | 541.646 | 0.0917% | 1,707.454 | 0.1951% |
| Non-combinational, including registers | 16,391.578 | 2.7752% | 44,985.234 | 5.1389% |
| SRAM macros | 560,640.809 | 94.9206% | 791,492.906 | 90.4161% |
| Total cell area | 590,642.137 | 100% | 875,389.531 | 100% |

Totals convert to 0.590642 mm² and 0.875390 mm², respectively. They exclude
routing and placement whitespace; they are not chip area or power estimates.
There are 68 and 96 mapped physical SRAM macros. Logical arrays implemented
as registers or combinational lookup logic are not additional physical SRAM
macros. Hierarchical subtotals already belong to the table and must not be
added again.

## What is available

This is a checked numerical summary of archived runs, not a new synthesis
execution. Technology and SRAM libraries, original commercial-tool scripts,
constraints, reports, mapped netlists and snapshots are not redistributed
because of licensing and confidentiality restrictions.

The repository provides Chisel generation and functional/performance
simulation, not public reproduction of this technology-specific synthesis.
See [Reproduction](reproduction.md) for commands and
[Results and limitations](results.md) for measured cycles and
workloads. Setup synthesis is not place-and-route signoff, board/silicon
validation, portable maximum frequency or commercial-IP equivalence.
