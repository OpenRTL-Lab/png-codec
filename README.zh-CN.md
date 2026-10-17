<!-- OpenRTL file declaration
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: png-codec
Repository introduction: Standalone lossless PNG encoder and decoder.
File: README.zh-CN.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# PNG 编解码硬件加速器

本仓库提供使用 Chisel 开发、附带生成 SystemVerilog 的独立 PNG 无损编码器与解码器。它在原始图像行与完整 PNG 文件之间转换，可通过原生流接口集成到 ASIC 或 FPGA，用于图像采集、存储、传输及处理链路。

[English](README.md)

采用 [MIT许可证](LICENSE)，允许商用、修改及再分发，须保留版权和许可声明。依赖保留各自许可，见 [第三方声明](THIRD_PARTY_NOTICES.zh-CN.md)。

[仓库内容](#仓库内容) · [应用场景](#应用场景) · [性能指标](#性能指标) · [实现协议](#实现协议) · [快速复现](#快速复现) · [验证范围与限制](#验证范围与限制) · [综合结果](#综合结果) · [公开边界](#公开边界) · [OpenRTL 定制与集成](#openrtl-定制与集成)

## 仓库内容

- `src/main/` 和 `build.mill`：Chisel 产品源码及固定的生成器配置。
- `rtl/encode/rtl.f` 和 `rtl/decode/rtl.f`：两套独立产品清单，编码 15 份、解码 24 份 SystemVerilog；必须分别编译。
- `src/tool/`、`reference/`、`sim/` 和 `tools/`：验证环境、固定软件参考适配器、时钟驱动及生成/验证命令。
- [架构说明](docs/architecture.zh-CN.md)、[原生接口](docs/interface.zh-CN.md)、[快速复现](docs/reproduction.zh-CN.md)、[结果与边界](docs/results.zh-CN.md)和 [综合结果](docs/synthesis-summary.zh-CN.md)：实现、集成、复现及实测结果。

## 应用场景

系统只要需要在下文支持范围内进行原始样本行与 PNG 文件的无损转换，就可以使用本模块。例如保存采集图像、存取嵌入式图像资源，或在传输前后进行图像编解码。这些是集成示例，不限定使用场景，也不代表已测得这些系统的端到端性能。硬件保留所选样本格式，包括 16 位和索引数据，而非统一展开为 RGBA。主机控制、DMA 和系统缓冲由外围提供。

## 性能指标

正常集合中持续速率最慢的两项配置，原始图像均为 65,536 字节；命令接收到完成的完整延迟／持续任务间隔，编码为 66,876／66,877 周期，解码为 68,230／68,231 周期。持续间隔由同实例 32 个相同图像测得。假定 500 MHz，延迟换算为 133.752／136.460 µs，不是板级或硅后实测。

425 个正常配置均达到本项目 0.95 原始字节/周期目标。**单独的每 IDAT 一字节压力项仅为 0.062090534168 原始字节/周期，未达其 0.89 报告型目标。**负载、首图像延迟、精确公式和压力结果见 [结果与边界](docs/results.zh-CN.md)。

## 实现协议

硬件实现静态非交错 PNG 的完整文件封装、CRC、行滤波、zlib 及 DEFLATE。支持全部 15 种合法颜色/位深组合，宽高 1～8192、原始行最多 65,536 字节、PLTE/tRNS 元数据合计最多 1,024 字节。编码按每图所选固定行滤波，采用 STORED 或 FIXED 块；解码另支持 DYNAMIC 及混合块。

STORED 表示不压缩的块，FIXED 使用固定 Huffman 表，DYNAMIC 随流传输码表。原生接口采用 64 位 valid/ready 流、同步复位及背压/错误恢复；成功完成前输出只是候选。字节序、计数和存储语义见 [原生接口](docs/interface.zh-CN.md)，数据路径见 [架构说明](docs/architecture.zh-CN.md)。

## 快速复现

先克隆仓库，按 [快速复现](docs/reproduction.zh-CN.md)安装对应版本，包括 Python 3.12、JDK 21、Mill 1.1.7 和 Verilator 5.050。在仓库根目录执行：

```sh
python3 tools/check_publication.py
python3 tools/test_source_headers.py
python3 tools/public_verify.py lint
python3 tools/prepare_reference.py --jobs 1
python3 tools/public_verify.py smoke --out build/smoke --limit 2
```

预期标记：`PNG_PUBLIC_HEADERS_PASS`、`PNG_RELEASED_LINT_PASS`、`PNG_RELEASED_SMOKE_PASS`。短仿真对双向各两个文件与固定软件核对，另含故意改错的解码期望字节。使用新的输出目录。重新生成、较大回归和性能命令见 [快速复现](docs/reproduction.zh-CN.md)。

## 验证范围与限制

已有功能回归覆盖 152 个编码、228 个解码文件，另含协议错误、背压、复位恢复及完整 8192×8192 的 1 位灰度图，不代表测过全部最大格式/纹理。功能测试、计时任务和离线文件检查是不同集合；计数及软件参考的协议边界差异见 [结果与边界](docs/results.zh-CN.md)。

Adam7、APNG、动态 Huffman 编码、自动滤波、量化、颜色管理、GZIP/ZIP 和预置字典不在范围。可移植命令的实际运行使用预装工具及公开 Maven 预热缓存，不证明空机首次安装或离线引导；执行记录与限制见 [发布检查](docs/publication-checks.zh-CN.md)。

## 综合结果

两个完整顶层采用 Design Compiler NXT，在 TSMC 28 nm HPC+、SSG 0.81 V／−40 °C、500 MHz、10% setup 时钟不确定度下通过 setup。已报告的最差 setup 路径中，编码 100%、解码 99.8713% 按 Z/ZN 输出引脚计数的级数小于 50。这是特定目标综合，不等于布局布线签核或板级验证。完整条件、计数定义、裕量、面积及 SRAM 资源见 [综合结果](docs/synthesis-summary.zh-CN.md)。

## 公开边界

提供原创 Chisel、生成 RTL、生成/仿真命令、双语文档及综合摘要。受许可/保密要求限制，不分发工艺/SRAM 库、商业 EDA、原始综合脚本/约束/报告、映射网表及快照。不提供 SoC、DMA 引擎或上板工程。

## OpenRTL 定制与集成

[OpenRTL](https://github.com/OpenRTL-Lab) 开发可复用 ASIC/FPGA 加速器。需要编解码定制、存储/总线集成或性能/资源优化时，请通过组织主页或 [OpenRTL 官网](https://openrtl.top/)联系，提供图像格式、目标工艺、接口及性能/资源目标。

定制是可选服务，MIT 商用不以购买服务为前提。请勿在公开 Issue 中提交保密规格。
