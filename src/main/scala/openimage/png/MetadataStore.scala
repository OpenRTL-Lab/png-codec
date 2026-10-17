// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: MetadataStore.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._

/** One checked 64-bit metadata handshake writes eight disjoint byte banks.
  * Collection completes before the file formatter reads this generation.
  */
class MetadataStore extends Module {
  val io=IO(new Bundle {
    val start=Input(Bool());val length=Input(UInt(32.W));val discard=Input(Bool())
    val in=Flipped(Decoupled(new StreamBeat))
    val readAddress=Input(UInt(10.W));val readData=Output(UInt(8.W))
    val received=Output(UInt(32.W));val closed=Output(Bool());val failed=Output(Bool())
    val acceptedGood=Output(Bool())
    val checkedBeat=Output(new StreamBeat)
  })
  val banks=Seq.fill(8)(Mem(128,UInt(8.W)))
  val active=RegInit(false.B);val closed=RegInit(true.B);val failed=RegInit(false.B)
  // Private metadata storage is bounded to 1024 bytes. Even an invalid final
  // beat can advance at most to 1031 before closed, so eleven bits are exact.
  val length=Reg(UInt(11.W));val received=RegInit(0.U(11.W))
  val ingress=withReset(reset.asBool || io.start){Module(new Queue(new StreamBeat,1,pipe=true,flow=false))}
  val checked=ingress.io.deq;checked.ready:=active && !closed
  io.checkedBeat:=checked.bits
  val k=PopCount(checked.bits.keep);val next=received+&k
  val mask=((1.U(9.W)<<k)-1.U)(7,0)
  val bad=k===0.U || checked.bits.keep=/=mask || (!checked.bits.last && k=/=8.U) ||
    next>length || (checked.bits.last=/=(next===length))
  // Replacement is allowed only behind a registered nonterminal beat. No
  // current external data/keep feeds ready or bypasses the checking register.
  val terminalPending=checked.valid && (checked.bits.last || next>=length)
  ingress.io.enq.valid:=io.in.valid && active && !closed && !terminalPending
  ingress.io.enq.bits:=io.in.bits
  io.in.ready:=ingress.io.enq.ready && active && !closed && !terminalPending
  when(terminalPending){assert(!io.in.fire,"PNG_METADATA_NO_BEHIND_END")}
  io.acceptedGood:=checked.fire && !bad && !failed && !io.discard
  io.received:=received;io.closed:=closed;io.failed:=failed
  io.readData:=VecInit(banks.map(_(io.readAddress(9,3))))(io.readAddress(2,0))
  when(io.acceptedGood) {
    assert(received(2,0)===0.U && next<=1024.U,"PNG_METADATA_BANK_WRITE")
    for(i<-0 until 8) when(i.U<k) {
      banks(i).write(received(9,3),checked.bits.data(8*i+7,8*i))
    }
  }
  when(checked.fire) {
    assert(next<=1031.U,"PNG_METADATA_COUNT_BOUND")
    received:=next
    when(checked.bits.last || next>=length) { closed:=true.B }
    when(bad) { failed:=true.B }
  }
  when(io.start) {
    assert(io.length<=1024.U,"PNG_METADATA_LENGTH_BOUND")
    active:=true.B;length:=io.length(10,0);received:=0.U
    closed:=io.length===0.U;failed:=false.B
  }
}
