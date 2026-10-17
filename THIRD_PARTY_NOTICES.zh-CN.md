<!-- OpenRTL file declaration
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: png-codec
Repository introduction: Standalone lossless PNG encoder and decoder.
File: THIRD_PARTY_NOTICES.zh-CN.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# 许可与依赖来源

本说明区分本仓MIT原创部分与各自采用独立许可的外部工具/库。版本描述文档所用验证环境，
不表示依赖软件被捆绑到硬件中。

[English](THIRD_PARTY_NOTICES.md)

原创硬件/验证/C参考适配/发布工具按原MIT和2026 OpenImage contributors版权，品牌不代表转让。
图像数学生成，不再分发外来语料；生成SV保留CIRCT声明和原硬件正文。编译/验证依赖不因此
改许可为MIT。外部依赖的固定版本、许可和公开来源如下；JDK按具体发行许可。

| 外部依赖 | 版本／许可 | 官方取得方式 |
| --- | --- | --- |
| libpng | 1.6.59／PNG Reference Library License v2 | [libpng源压缩包](https://codeload.github.com/pnggroup/libpng/tar.gz/refs/tags/v1.6.59) |
| zlib | 1.3.2／zlib许可 | [zlib源压缩包](https://zlib.net/zlib-1.3.2.tar.gz) |
| Mill | 1.1.7／MIT | [Mill](https://mill-build.org/) |
| Scala | 2.13.18／Apache-2.0 | [Scala](https://www.scala-lang.org/) |
| Chisel | 7.13.0／Apache-2.0 | [Chisel](https://github.com/chipsalliance/chisel) |
| CIRCT firtool | 1.149.0／Apache-2.0及LLVM例外 | [CIRCT](https://github.com/llvm/circt) |
| Verilator | 5.050／LGPL-3.0或Artistic-2.0 | [Verilator](https://verilator.org/) |
| JDK | 21／由发行版决定，常见为GPLv2及Classpath例外 | [OpenJDK](https://jdk.java.net/) |
| Python | 3.12／PSF许可 | [Python](https://www.python.org/) |
| GCC／CMake／Make | 主机构建工具，各自许可 | 从各官方发行源安装 |

仅保留未改的[libpng许可](licenses/libpng-LICENSE)与[zlib许可](licenses/zlib-LICENSE)法律文本，
不捆绑依赖实现、二进制、工具链、商业EDA、PDK/SRAM库或映射网表。
`tools/prepare_reference.py`从上述公开地址下载，并核对以下SHA-256：

```text
libpng-1.6.59.tar.gz SHA256 2540302a1844ad2b2b501977abecfa850f265f97b78f065a712ab4074a89f5b5
zlib-1.3.2.tar.gz SHA256 bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16
```

清单区分原创、生成、适配/新增及法律文本；
JSON/VERSION/清单等严格格式不插注释，由.license伴随文件逐项标明许可，不改变原文件语法。
文档修订会更新来源清单中的相应摘要，不能把“不插注释”理解成JSON清单永不改变；法律文本不加原创头。
SOURCE_MANIFEST自身由Git blob保证，不可能包含自身摘要，其余文件全部有SHA。
published_sha256是完整分发文件摘要；body_sha256是精确去除新增声明注释后的正文摘要，
无行内声明时则为完整文件摘要。原始来源摘要另记，适配文件明确分类，不称原件未改。
测试台存储初始化只提供测试向量，不属于集成时使用的编解码产品清单。MIT商业用途无需购买可选服务。
