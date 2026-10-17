// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: zlib_clock_main.cpp
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
#include "VZlibSuite.h"
#include "verilated.h"
#include <memory>
#include <cstdio>
int main(int argc,char **argv){
    auto context=std::make_unique<VerilatedContext>();context->commandArgs(argc,argv);context->threads(1);
    auto top=std::make_unique<VZlibSuite>(context.get());
    for(unsigned long long cycle=0;cycle<20000010;cycle++){
        top->reset=cycle<8;top->clock=0;top->eval();context->timeInc(1);
        top->clock=1;top->eval();context->timeInc(1);
        if(cycle>=8 && top->io_finished){top->final();return 0;}
        if(context->gotFinish())return 1;
    }
    std::fprintf(stderr,"ZLIB_CLOCK_TIMEOUT\n");return 1;
}
