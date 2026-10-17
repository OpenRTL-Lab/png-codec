<!-- OpenRTL file declaration
Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
Organization introduction: Open hardware, from algorithms to RTL.
Repository: png-codec
Repository introduction: Standalone lossless PNG encoder and decoder.
File: reproduction.zh-CN.md
Website: https://openrtl.top/
SPDX-License-Identifier: MIT
-->
# 快速复现

本指南供RTL开发者检查发布电路、从Chisel重新生成，以及按需运行较大的测试集合。
先做轻量检查和短仿真；扩展测试独立执行，成本更高。命令均在克隆根目录运行，
每个--out必须是build下尚不存在的新目录；产物和日志留在那里，不写回源码。

[English](reproduction.md)

全部命令在克隆根目录运行，需要Linux/Git/Python3.12/JDK21/Mill1.1.7/Scala2.13.18/
Chisel7.13.0/firtool1.149.0/Verilator5.050/Make/CMake/C++17；历史GCC16.2.0。
不同版本不保证生成逐字相同。MILL可指自己的启动器，例如执行
`export MILL='bash /your/tools/mill'`，无需私有路径；依赖来源/许可见
[第三方声明](../THIRD_PARTY_NOTICES.zh-CN.md)，首次Java/Scala/Chisel解析需联网，没有整套离线工具链。
网络失败不是RTL失败，--help也不等于空机安装成功。

## 轻量检查

```sh
python3 tools/check_publication.py
python3 tools/test_source_headers.py
python3 tools/public_verify.py lint
python3 tools/prepare_reference.py --jobs 1
```

前两项检查核完整库存、声明、正文SHA及缺失/重复/篡改/额外文件负向对照，不是硬件仿真。
负向对照指故意改坏预期值或文件，要求检查器拒绝，任意编译失败不能算检测成功。
lint两独立相对清单，仅解析非综合；prepare_reference公开下载核SHA并本地构建。
## 发布RTL短仿真

```sh
python3 tools/public_verify.py smoke --out build/smoke --limit 2
```

短smoke生成build/smoke/released-input.json，列出产品文件及SHA-256摘要；后续检查拒绝
与该清单不同的文件。Chisel BlackBox只实例化发布SystemVerilog（SV），不添加带时钟状态的
适配器，不重新实现编解码。双向各两完整文件、固定软件互通及指定解码字节负向对照真实执行。
必须命中PNG_DECODER_BYTE_MISMATCH，不能用任意编译失败替代。
C++只提供时钟/复位/触发，不代替像素答案；编译输入及前后摘要写qualification-compile.json。
可选产品断言绑定层是额外检查模块，不是编解码实现；本仓未捆绑它们，但测试台断言真实编译。
图像均数学生成，无未知语料。

smoke不是完整回归；limit150只覆盖编码150基本组合和解码前150项，不是228项。

## 从Chisel重新生成

```sh
python3 tools/public_verify.py emit --out build/regenerated
```

emit按固定环境重新生成两顶层，逐字比较原正文、补声明后再比发布字节。
这里的声明是新增版权/许可注释，不是PNG文件头；重复添加不改变文件（幂等），保留生成器声明。
输出已存在拒绝。可选生成断言层只是工具产物，不混算39份发布SV；正文不同须定位，不能静默替换。

## 扩展功能与性能入口

短仿真后，先导出它产生的文件清单路径及摘要，随后测试才会编译同一套独立发布SV：

```sh
export OPENIMAGE_PNG_EXTERNAL="$PWD/build/smoke/released-input.json"
export OPENIMAGE_PNG_EXTERNAL_SHA256="$(python3 -c 'import hashlib;from pathlib import Path;print(hashlib.sha256(Path("build/smoke/released-input.json").read_bytes()).hexdigest())')"
python3 tools/verify_encoder.py --out build/full-encoder --jobs 1
python3 tools/verify_decoder.py --out build/full-decoder --jobs 1
python3 tools/verify_encoder_protocol.py --out build/encoder-protocol --jobs 1
python3 tools/verify_decoder_edges.py --out build/decoder-protocol --source build/full-decoder --jobs 1
```

前两项默认分别为152编码/228解码基本文件。alpha或边界选项选择单独集合，不自动加入基本计数。
每测试换新输出目录，具体alpha/边界/复位/负向对照选项见各入口--help。最大图不包含在smoke内：

```sh
python3 tools/verify_large.py --out build/large-encode --direction encode --jobs 1
python3 tools/verify_large.py --out build/large-decode --direction decode --jobs 1
python3 tools/verify_performance.py --out build/perf-encode --direction encode --main-matrix --product-base HEAD --jobs 1
python3 tools/verify_performance.py --out build/perf-decode --direction decode --main-matrix --product-base HEAD --jobs 1
```

主性能170个编码/255个解码配置，每配置单独测一次冷启动，再测同实例32个连续任务。
一任务是一幅完整图像。B为原始图像字节，不含PNG封装、滤波类型和独立元数据；事件日志/CSV
区分原始字节、文件字节和像素。L为命令到完成握手的周期差、不加一；c1/c32为32个连续任务首尾完成握手。
II=(c32-c1)/31，持续原始吞吐B/II。这些为项目测试目标而非PNG标准要求。
编码首次图像定位重建首样本所需的输出字节，不计滤波类型字节；解码取首原始样本拍。
逐配置检查20*B>=19*L及20*31*B>=19*(c32-c1)，不合计并行实例吞吐。
启动延迟上限和测试集合区别详见[结果与边界](results.zh-CN.md#测量定义)。
六项解码压力测试是在解码性能命令中用--supplement pressure替换--main-matrix，并换新输出目录。
第六项即从0编号的case 5（idat-single-byte），每个IDAT只放一个压缩字节，已知未达仅报告的
0.89原始字节/周期建议。因此Python命令退出1，即使正常RTL仿真成功也如此。识别该结果必须同时核对：
completion.json中status为1、performance_contract.failed_contract_cases严格为[5]；
simulation-exit.json的returncode为0；negative-exit.json为非零退出且指定断言为
PNG_PERF_TAG_NEGATIVE，表示故意改错任务tag被检测。文件缺失、任意编译失败或其他失败项
均不属于这个预期数值缺口，不能用||true吞掉全部失败。

公开保留扩展入口，本次整理没有重新全矩阵/历史质量基线
或综合，不捆绑运维日志。原参考路径若默认PIE不兼容可明确CFLAGS=-no-pie。
