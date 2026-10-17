// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: WideBytes.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._

/** 仅内部使用，低字节先行；bytes只取1或2，外部ABI不变。 */
class WideBytes extends Bundle {
  val data=UInt(16.W)
  val bytes=UInt(2.W)
  val last=Bool()
}

/** 1/2字节→64位，允许跨输出拍的余字节，不撤销已发布的valid。 */
class WideBytePacker extends Module {
  val io=IO(new Bundle {
    val in=Flipped(Decoupled(new WideBytes));val out=Decoupled(new StreamBeat)
    val clear=Input(Bool());val empty=Output(Bool())
  })
  val data=RegInit(0.U(80.W));val count=RegInit(0.U(4.W));val ending=RegInit(false.B)
  io.out.valid:=count>=8.U || (ending && count=/=0.U)
  io.out.bits.data:=data(63,0)
  val outCount=Mux(count>=8.U,8.U(4.W),count)
  io.out.bits.keep:=((1.U(9.W)<<outCount)-1.U)(7,0)
  io.out.bits.last:=ending && count<=8.U
  val retired=Mux(io.out.fire,outCount,0.U(4.W))
  val baseCount=count-retired
  val baseData=Mux(io.out.fire,data>>64,data)
  io.in.ready := !ending && (baseCount+&io.in.bits.bytes)<=10.U
  io.empty:=count===0.U
  when(io.out.fire) { count:=baseCount;data:=baseData }
  when(io.in.fire) {
    assert(io.in.bits.bytes>=1.U && io.in.bits.bytes<=2.U,"WIDE_PACK_BYTES")
    val payload=Mux(io.in.bits.bytes===1.U,io.in.bits.data(7,0).pad(16),io.in.bits.data)
    data:=baseData | (payload.pad(80)<<(baseCount<<3))(79,0)
    count:=baseCount+io.in.bits.bytes
    when(io.in.bits.last) { ending:=true.B }
  }
  when(io.clear) { data:=0.U;count:=0.U;ending:=false.B }
}
