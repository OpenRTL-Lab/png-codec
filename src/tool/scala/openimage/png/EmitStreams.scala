// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: EmitStreams.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import java.nio.file.{Files,Paths}

/** 独立公式检查64→8→64，两种接收策略和所有末拍长度。 */
class StreamAdapterSuite(negative: Boolean) extends Module {
  val io=IO(new Bundle { val finished=Output(Bool()) })
  val input=Module(new ByteInput);val pack=Module(new BytePacker)
  val sizes=(1 to 17)++(63 to 72)++Seq(256,65543)
  val lengths=VecInit(sizes.map(_.U(32.W)))
  val task=RegInit(0.U(7.W));val index=(task>>1)(4,0)
  val length=lengths(index);val randomMode=task(0)
  val phase=RegInit(0.U(2.W))
  val source=RegInit(0.U(32.W));val sink=RegInit(0.U(32.W))
  val cycles=RegInit(0.U(32.W));val started=Reg(UInt(32.W))
  cycles:=cycles+1.U
  val random=RegInit("hd13571af".U(32.W))
  random:=Cat(random(30,0),random(31)^random(21)^random(1)^random(0))
  def sample(address: UInt): UInt=((address*73.U+17.U)^(address>>3))(7,0)
  val count=Mux(length-source>=8.U,8.U(4.W),(length-source)(3,0))
  input.io.start:=phase===0.U;input.io.length:=length;input.io.discard:=false.B
  input.io.in.valid:=phase===1.U && source<length
  input.io.in.bits.keep:=((1.U(9.W)<<count)-1.U)(7,0)
  input.io.in.bits.last:=source+count===length
  input.io.in.bits.data:=Cat((0 until 8).reverse.map(i=>Mux(i.U<count,sample(source+i.U),0.U(8.W))))
  pack.io.in<>input.io.out;pack.io.clear:=false.B
  pack.io.out.ready := !randomMode || random(1,0)=/=0.U
  when(input.io.in.fire) { source:=source+count }
  val stalled=RegNext(pack.io.out.valid && !pack.io.out.ready,false.B)
  val previous=RegEnable(pack.io.out.bits,pack.io.out.valid)
  when(stalled) { assert(pack.io.out.valid && pack.io.out.bits.asUInt===previous.asUInt,"STREAM_STABILITY") }
  when(pack.io.out.fire) {
    val n=Mux(length-sink>=8.U,8.U(4.W),(length-sink)(3,0))
    assert(pack.io.out.bits.keep===((1.U(9.W)<<n)-1.U)(7,0),"STREAM_KEEP")
    assert(pack.io.out.bits.last===(sink+n===length),"STREAM_LAST")
    for(i<-0 until 8) {
      val corrupt=if(negative) task===0.U && i.U===0.U else false.B
      when(i.U<n) { assert(pack.io.out.bits.data(8*i+7,8*i)===(sample(sink+i.U)^corrupt.asUInt),"STREAM_BYTE_MISMATCH") }
    }
    sink:=sink+n
    when(pack.io.out.bits.last) {
      assert(input.io.closed && !input.io.failed && input.io.received===length && source===length,"STREAM_ACCOUNTING")
      // One explicit ingress-register cycle, then exactly one byte per cycle.
      when(!randomMode) { assert(cycles-started===length+3.U,"STREAM_CONTINUOUS_RATE") }
      printf(p"STREAM_CASE_PASS task=${task} bytes=${length} cycles=${cycles-started}\n")
      phase:=0.U;task:=task+1.U
      when(task===(sizes.length*2-1).U) { phase:=2.U;printf(p"STREAM_SUITE_PASS tasks=${task+1.U}\n") }
    }
  }
  when(phase===0.U) { phase:=1.U;source:=0.U;sink:=0.U;started:=cycles }
  io.finished:=phase===2.U
  assert(cycles<1000000.U,"STREAM_WATCHDOG")
}
object EmitStreams extends App {
  require(args.length==2)
  val out=Paths.get(args(0));require(!Files.exists(out));Files.createDirectories(out)
  ChiselStage.emitSystemVerilog(new StreamAdapterSuite(args(1)=="negative"),
    firtoolOpts=Array("-O=release","-disable-all-randomization","-split-verilog",s"-o=$out"))
}
