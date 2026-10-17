// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: clock_main.cpp
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
// 仅时钟、复位及验证顶层结束状态；不驱动DUT数据或内部存储。
#include "VPngEncoderSample.h"
#include "verilated.h"
#include <memory>
#include <cstdio>
int main(int argc,char **argv) {
    auto context=std::make_unique<VerilatedContext>();
    context->commandArgs(argc,argv); context->threads(1);
    auto top=std::make_unique<VPngEncoderSample>(context.get());
    for (unsigned long long cycle=0;cycle<1000010;cycle++) {
        top->reset=cycle<8;
        top->clock=0;top->eval();context->timeInc(1);
        top->clock=1;top->eval();context->timeInc(1);
        if(cycle>=8 && top->io_finished) { top->final();return 0; }
        if(context->gotFinish()) return 1;
    }
    std::fprintf(stderr,"CLOCK_WRAPPER_TIMEOUT\n");return 1;
}
