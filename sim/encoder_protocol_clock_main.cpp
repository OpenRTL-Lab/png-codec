// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: encoder_protocol_clock_main.cpp
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
#include "VEncoderProtocolSuite.h"
#include "verilated.h"
int main(int argc,char **argv){
  VerilatedContext context;context.commandArgs(argc,argv);context.threads(1);
  VEncoderProtocolSuite top{&context};
  for(unsigned long long cycle=0;cycle<2000010ULL && !context.gotFinish();cycle++){
    top.reset=cycle<5;top.clock=0;top.eval();context.timeInc(1);
    top.clock=1;top.eval();context.timeInc(1);
    if(cycle>5 && top.io_finished){top.final();return 0;}
  }
  top.final();return 1;
}
