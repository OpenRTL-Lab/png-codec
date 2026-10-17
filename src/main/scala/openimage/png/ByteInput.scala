// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: ByteInput.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._

/** 接受一次64位拍后逐字节消费；声明边界/提前last即关闭，不等待补拍。 */
class ByteInput extends Module {
  val io=IO(new Bundle {
    val start=Input(Bool());val length=Input(UInt(32.W));val discard=Input(Bool())
    val in=Flipped(Decoupled(new StreamBeat));val out=Decoupled(new ByteBeat)
    val received=Output(UInt(32.W));val closed=Output(Bool());val failed=Output(Bool())
  })
  val active=RegInit(false.B);val closed=RegInit(true.B);val failed=RegInit(false.B)
  val length=Reg(UInt(32.W));val received=RegInit(0.U(32.W));val consumed=RegInit(0.U(32.W))
  val data=Reg(UInt(64.W));val count=RegInit(0.U(4.W))
  // Lock external keep/data before validation. Prefetch one whole beat while
  // the previous beat is being consumed; never accept behind an unchecked last.
  val ingress=withReset(reset.asBool || io.start){Module(new Queue(new StreamBeat,1,pipe=false,flow=false))}
  ingress.io.enq.valid:=io.in.valid && active && !closed
  ingress.io.enq.bits:=io.in.bits
  io.in.ready:=ingress.io.enq.ready && active && !closed
  val checked=ingress.io.deq
  // Retire the old beat's final byte and replace it on the same edge.
  // The later in.fire assignment has priority over the old-byte shift.
  checked.ready:=active && !closed && (io.discard || count===0.U || (count===1.U && io.out.ready && io.out.valid))
  io.out.valid:=active && !io.discard && !failed && count=/=0.U
  io.out.bits.data:=data(7,0);io.out.bits.last:=consumed+1.U===length
  io.received:=received;io.closed:=closed;io.failed:=failed
  when(io.out.fire){data:=data>>8;count:=count-1.U;consumed:=consumed+1.U}
  when(io.discard){count:=0.U}
  when(checked.fire){
    val k=PopCount(checked.bits.keep);val next=received+&k
    val mask=((1.U(9.W)<<k)-1.U)(7,0)
    val bad=k===0.U || checked.bits.keep=/=mask || (!checked.bits.last && k=/=8.U) ||
      next>length || (checked.bits.last=/=(next===length))
    received:=next
    when(checked.bits.last || next>=length){closed:=true.B}
    when(!io.discard){data:=checked.bits.data;count:=k}
    when(bad){failed:=true.B;count:=0.U}
  }
  when(io.start){
    active:=true.B;length:=io.length;received:=0.U;consumed:=0.U;count:=0.U
    closed:=io.length===0.U;failed:=false.B
  }
}

class PngCompletion extends PngResult {
  val imageWidth=UInt(14.W);val imageHeight=UInt(14.W)
  val colorType=UInt(3.W);val bitDepth=UInt(5.W)
  val paletteBytes=UInt(10.W);val transparencyBytes=UInt(9.W)
}
