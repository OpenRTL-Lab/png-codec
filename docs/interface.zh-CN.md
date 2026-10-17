<!-- OpenRTL file declaration
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: png-codec
Repository introduction: Standalone lossless PNG encoder and decoder.
File: interface.zh-CN.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# 原生 PNG 接口

本说明面向RTL集成者，解释如何提交一幅完整图像、何时能使用输出。PngEncoder和PngDecoder
为两个独立顶层，不是串联流水线。字段以[Protocol.scala](../src/main/scala/openimage/png/Protocol.scala)为准。单clock、同步高有效reset，
所有通道仅valid&&ready交付，背压时负载逐位保持。cmd开始任务，done被接收后才能开始下一任务；
只有reset可取消任务且不产生完成。

[English](interface.md)

文件层面在有界静态非交错范围内遵循
[PNG 规范](https://www.w3.org/TR/png/)，由
[zlib 格式](https://www.rfc-editor.org/rfc/rfc1950)封装
[DEFLATE 格式](https://www.rfc-editor.org/rfc/rfc1951)。
STORED 块直接携带未压缩字节，FIXED 和 DYNAMIC 分别使用固定和随流传输的
Huffman 表。编码支持 STORED 或 FIXED；解码另支持 DYNAMIC 和混合块。
这些是文件格式模式，不是不同总线协议或生成器变体。

| 命令字段 | 位宽与含义 |
| --- | --- |
| `tag` | 32 位任务标签，复制到图像描述及完成信息 |
| `inputBytes`、`outputLimit` | 各 32 位，合法值 1～2^30；分别为输入字节数和输出字节上限 |
| `imageWidth`、`imageHeight` | 各 14 位，合法宽高 1～8192 |
| `colorType`、`bitDepth` | 分别为 3 位颜色类型和 5 位样本位深；合法组合见下文 |
| `filter`、`fixed` | 分别为 3 位滤波选择（0～4）和 1 位编码块模式（0 为 STORED，1 为 FIXED） |
| `paletteBytes`、`transparencyBytes` | 分别为 10 位 PLTE 长度和 9 位 tRNS 长度，合计不超过 1024 字节 |

颜色类型/位深组合为：灰度type0的1/2/4/8/16位，RGB type2的8/16位，索引type3的1/2/4/8位，
灰度透明type4的8/16位，RGBA type6的8/16位，共15种；每行原始字节最多65536。
filter值0/1/2/3/4分别为None/Sub/Up/Average/Paeth；fixed选择编码DEFLATE块类型，不是生成器配置。
解码仅用tag/输入长度/输出容量，其余从IHDR解析。编码inputBytes只计原始图像、outputLimit限制
完整PNG；解码inputBytes计包含全部元数据块的完整PNG、outputLimit限制原始图像输出。
独立元数据通道字节不加入原始图像计数。

StreamBeat含64位data、8位keep和1位last。keep每位表示对应字节位置有效，data[7:0]最先，
非尾keep=ff，尾keep低连续非零；禁止空拍。last只标整个文件/原图/元数据序列末尾，不标行末。
元数据独立通道依次传PLTE、tRNS。编码由paletteBytes/transparencyBytes声明其长度，不计原始
inputBytes；解码元数据块包含在PNG输入长度中，其独立输出不计原始outputBytes。没有元数据就不发拍。
解码descriptor是图像描述，含tag/宽高/颜色/位深/两元数据长度，先于元数据及原始图像输出。

R=ceil(宽×通道×位深/8)，原图R×高，行连续且不添加额外stride填充，但末字节仍可能有未用低位。
17像素1位灰度行占3字节，第3字节低7位未用。总线字节位置顺序与样本字节序不同：
16位样本0x1234先将0x12放入较早的流字节位置，再传0x34。
16位高字节先，低位深高位先；编码每行未用尾位
须零，解码对外清零但内部保留重建原字节供下一行预测。索引必须在实际PLTE内；灰度/RGB透明键
符合位深，RGB/RGBA建议调色板仍保留为元数据，不作palette展开或颜色转换。

完成报告含tag、3位status、实际输入输出字节及pixels，解码另由图像描述通道报告解析格式。
原始图像计数排除独立元数据，PNG文件计数包含全部块。状态0成功，1非法命令，2不支持容量/格式范围，
3文件/压缩/样本错误，4CRC/Adler，5输入长度/拍格式，6容量不足。首检测错误锁存；非法命令不收
输入，已将valid置高的描述/元数据/输出保持至握手，排空当前声明或提前last边界后done，不吞下一任务。
最终OK前输出只是候选，错误必须丢弃；输出前预留容量，不超outputLimit。错误完成后无需reset可重入；
reset撤销各输出valid并清除当前任务状态，可接新命令，不伪造成功或取消完成。
DMA/总线错误/缓存一致性/错误输出丢弃由外部负责。
