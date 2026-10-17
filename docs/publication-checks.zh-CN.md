<!-- OpenRTL file declaration
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: png-codec
Repository introduction: Standalone lossless PNG encoder and decoder.
File: publication-checks.zh-CN.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# 发布检查

本说明记录2026-10-17实际执行的可移植命令及其边界，不是要求读者把每个历史步骤再跑一遍。
SV指SystemVerilog；正文指精确去除新增许可/组织声明注释后的内容，SHA-256分别标识正文和
完整分发文件。负向对照指故意改坏文件或预期答案，必须由指定检查器拒绝，不能用其他失败替代。
幂等指同一声明重复添加两次仍不改变文件。

[English](publication-checks.md)

以下六类新增命令已于2026-10-17真实执行并通过，是有界发布检查，不替代
[结果与边界](results.zh-CN.md)中的完整历史测试。

| 实际命令 | 观察结果 |
| --- | --- |
| `python3 tools/check_publication.py` | 原141文件库存、39份SV及来源/声明摘要检查通过；当前库存另见文末 |
| `python3 tools/test_source_headers.py` | 正文逐字往返、幂等、缺失/重复/篡改声明、正文变更和额外文件负向对照通过 |
| `python3 tools/public_verify.py lint` | Verilator 5.050启用断言，分别检查两套独立产品清单，通过 |
| `python3 tools/prepare_reference.py --jobs 1` | 下载固定公开源压缩包、核SHA-256并构建静态参考库，通过 |
| `python3 tools/public_verify.py emit --out build/regenerated-r1` | 双顶层重新生成，39份SV正文与原发布正文逐字相等，补声明后也与发布字节相等 |
| `python3 tools/public_verify.py smoke --out build/smoke-r1 --limit 2` | 编解码各两个完整文件及指定解码字节负向对照通过 |

smoke仅Gray1（1位灰度），宽17、编码高3/解码高5、None滤波、STORED/FIXED。编码完整文件106/101B，
FIXED真实LZ77匹配1次；编码输出由固定libpng/zlib核对，解码软件生成输入并逐原字节比较。
负控实际退出1且命中PNG_DECODER_BYTE_MISMATCH，不能用任意编译失败充当负控；不冒称
本次重跑全部15格式/所有错误/最大图。

Chisel BlackBox是对外部模块的实例化，不是编解码替代逻辑；它直接编译独立发布SV，
编译前后记录所有文件摘要，测试台断言启用。可选产品bind层是额外断言模块，不是产品数据路径，
本仓未捆绑；适配器报告的product_assertion_binds_included标志明确为False，硬件正文/数学刺激/核对算法未改。

版本由build.mill及复现说明固定；实际使用**预装工具及公开Maven预热缓存**，不证明空机安装、
离线bootstrap或其他编译器兼容。公开源压缩包下载实际执行，首次JVM制品下载未重做。
源生成出现六项既有Chisel弃用警告，不宣称零警告；SV末尾原有空行也保留，不为格式删正文。

测试后及后续读者文档修订都只改说明和对应manifest摘要，受测工具/RTL/源码字节不变。
main分支与版本标签v1.2.1标识同一份独立发布快照，仅一个根提交，不带内部开发历史。
增加独立综合摘要后，库存曾为142文件；现在将八对语言版本拆成独立文件，库存为150文件，
其中16份为说明文档。库存、声明及正文摘要检查覆盖当前快照；上面的141文件描述原先
那轮命令执行。新增的是文档，不是RTL或额外硬件测试。
完整功能/性能/特定综合为原版本证据；本次没有重复大矩阵/受限综合/后端/板测/硅后。
碎片IDAT报告型压力缺口仍在README和结果说明中保留。
