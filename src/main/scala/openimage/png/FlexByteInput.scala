// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: FlexByteInput.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._

/** Bounded 16-byte window; the parser chooses one or up to two bytes.
  * External beats are checked/accounted exactly once, independently of views. */
class FlexByteInput extends Module {
  val io=IO(new Bundle {
    val start=Input(Bool());val length=Input(UInt(32.W));val discard=Input(Bool());val single=Input(Bool())
    val in=Flipped(Decoupled(new StreamBeat));val out=Decoupled(new WideBytes)
    val received=Output(UInt(32.W));val closed=Output(Bool());val failed=Output(Bool())
  })
  val active=RegInit(false.B);val closed=RegInit(true.B);val failed=RegInit(false.B)
  val length=Reg(UInt(32.W));val received=RegInit(0.U(32.W));val consumed=RegInit(0.U(32.W))
  val data=RegInit(0.U(128.W));val count=RegInit(0.U(5.W))
  val ingress=withReset(reset.asBool || io.start){Module(new Queue(new StreamBeat,1,pipe=false,flow=false))}
  val accept=active && !closed && (io.discard || count<=8.U)
  ingress.io.enq.valid:=io.in.valid && accept;ingress.io.enq.bits:=io.in.bits
  io.in.ready:=ingress.io.enq.ready && accept
  val checked=ingress.io.deq
  val n=Mux(io.single || count===1.U,1.U(2.W),2.U(2.W))
  // A non-final singleton must wait for refill in two-byte mode. Otherwise
  // appending the next external beat could change bytes/data while stalled.
  io.out.valid:=active && !io.discard && !failed &&
    (count>=2.U || (count===1.U && (io.single || closed)))
  io.out.bits.data:=data(15,0);io.out.bits.bytes:=n;io.out.bits.last:=consumed+n===length
  val retired=Mux(io.out.fire,n,0.U(2.W));val baseCount=count-retired
  val baseData=data>>(retired<<3)
  checked.ready:=active && !closed && (io.discard || baseCount<=8.U)
  io.received:=received;io.closed:=closed;io.failed:=failed
  when(io.out.fire){data:=baseData;count:=baseCount;consumed:=consumed+n}
  when(io.discard){count:=0.U;data:=0.U}
  when(checked.fire){
    val k=PopCount(checked.bits.keep);val next=received+&k
    val mask=((1.U(9.W)<<k)-1.U)(7,0)
    val bad=k===0.U || checked.bits.keep=/=mask || (!checked.bits.last && k=/=8.U) ||
      next>length || (checked.bits.last=/=(next===length))
    received:=next
    when(checked.bits.last || next>=length){closed:=true.B}
    when(!io.discard){
      val bytesMask=((1.U(65.W)<<(k<<3))-1.U)(63,0)
      data:=baseData | ((checked.bits.data & bytesMask).pad(128)<<(baseCount<<3))(127,0)
      count:=baseCount+k
    }
    when(bad){failed:=true.B;count:=0.U;data:=0.U}
  }
  when(io.start){
    active:=true.B;length:=io.length;received:=0.U;consumed:=0.U;count:=0.U;data:=0.U
    closed:=io.length===0.U;failed:=false.B
  }
  assert(count<=16.U,"FLEX_INPUT_CAPACITY")
}
