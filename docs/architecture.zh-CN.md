<!-- OpenRTL file declaration
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: png-codec
Repository introduction: Standalone lossless PNG encoder and decoder.
File: architecture.zh-CN.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# 架构与存储

发布源码为PNG1.2.1原实现；build.mill固定生成版本，逐文件正文摘要与源码/RTL映射见
[来源清单](../SOURCE_MANIFEST.json)。源提交是来源标识，不是本仓可达内部历史。

[English](architecture.md)

## 编码数据路径

编码器输入原始样本字节及可选的PLTE/tRNS元数据，输出完整PNG文件。握手是某个时钟沿
通道的valid与ready同时为高；寄存输入检查先保存接收的64位数据拍，再检查字节掩码和结束标志。

路径为输入检查→字节入口→同步行滤波→行队列→WideZlibEncoder→IDAT封装/CRC→输出组拍→
输出队列。行队列暂存待处理字节，使下游短时停顿不必在同一拍传播至所有上游。
压缩器进行有界LZ77匹配搜索，并用位缓冲累积编码结果，凑足字节后输出；这就是RTL中的bit pool。

IDAT封装使用两个各2048字节的缓冲区，RTL所说的bank指这两个内部存储区，不是外部存储银行。
组拍器拼成64位输出拍；末级队列可保存两拍，禁止新输入绕过存储直接到输出（flow=false）。
内部可以提前预留空间，但对外报告的字节计数只在规定的实际握手时更新。
元数据同样寄存检查，并提前读取随后要用的字节。初始化算术分解为经过寄存的部分结果，
不改变原生命令/数据格式，详见[原生接口](interface.zh-CN.md)。

## 解码数据路径

解码器输入完整PNG，分别输出图像描述、PLTE/tRNS字节和原始样本字节。寄存输入进入
16字节滑动文件缓冲，再由PNG解析器检查结构及CRC。FastInflate采用64位压缩位缓冲、
规范Huffman查找表和32KiB的LZ77历史；逆行滤波恢复样本，再经过行队列/组拍到对外输出队列。

IDAT边界不结束zlib流，因此不清位缓冲、历史或Adler状态。最终完成前检查每块CRC及滤波后
图像数据的精确预期长度。当前匹配尚在复制时可以查看下一个匹配，但仅在本次复制结束边界
启动下一次复制。距离1/2的重叠复制通过转发刚产生的字节，避免读取尚未写入的历史位置。

## 存储与集成约定

同步RAM延迟/读写使能/边界/同址前递须逐块保持；部分有界Huffman符号查询有意采用异步
组合逻辑，不能把所有存储统一假定为一种宏。不能为时序暗改单拍读延迟或使能。
不提供物理宏/工艺库；复位撤销对外valid并清除当前任务上下文，不保证清零每个RAM位；
尚未由当前有效任务写入的数据不能被当作有效结果。

不提供外存控制器、AXI/DMA、板级原语或整帧缓存。内部按字节计算，外部64位不等于8原始B/c。
两方向同名模块可能不同，必须独立编译。本说明描述结构，不保证某个吞吐率；
完整任务的实际性能及测试负载定义见[结果与边界](results.zh-CN.md)。
