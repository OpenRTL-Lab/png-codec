// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: performance_clock_main.cpp
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
#include "VPerformanceSuite.h"
#include "verilated.h"
#include <cstring>
int main(int argc,char **argv){
  VerilatedContext context;context.commandArgs(argc,argv);context.threads(1);VPerformanceSuite top{&context};
  bool negative=false;for(int i=1;i<argc;i++)if(!std::strcmp(argv[i],"--negative"))negative=true;
  top.io_negative=negative;
  for(unsigned long long cycle=0;cycle<10000000010ULL && !context.gotFinish();cycle++){
    top.reset=cycle<5;top.clock=0;top.eval();context.timeInc(1);top.clock=1;top.eval();context.timeInc(1);
    if(cycle>5 && top.io_finished){top.final();return 0;}
  }
  top.final();return 1;
}
