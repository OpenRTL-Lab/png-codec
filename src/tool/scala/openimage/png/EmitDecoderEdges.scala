// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: EmitDecoderEdges.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage
import java.nio.file.{Files,Paths}
import scala.jdk.CollectionConverters._

class DecoderEdgeSuite(cases:Seq[Seq[BigInt]],dir:String,negative:String) extends Module {
  val io=IO(new Bundle{val finished=Output(Bool())})
  val cancel=WireDefault(false.B)
  val dut=withReset(reset.asBool || cancel){QualifiedCodec.decoder()}
  // W H type depth pngN pngOff rawN rawOff plen tlen metaOff status limit declared mode abort comparePrefix
  val table=cases.transpose.map(v=>VecInit(v.map(_.U(32.W))))
  val task=RegInit(0.U(math.max(1,log2Ceil(cases.size+1)).W))
  val index=task(math.max(1,log2Ceil(cases.size))-1,0)
  def field(n:Int)=table(n)(index)
  val pn=cases.map(c=>(c(4)+c(5)).toInt).max
  val rn=cases.map(c=>(c(6)+c(7)).toInt).max
  val mn=math.max(1,cases.map(c=>(c(8)+c(9)+c(10)).toInt).max)
  val png=Mem(pn,UInt(8.W));val raw=Mem(rn,UInt(8.W));val meta=Mem(mn,UInt(8.W))
  loadMemoryFromFileInline(png,s"$dir/png.hex");loadMemoryFromFileInline(raw,s"$dir/raw.hex");loadMemoryFromFileInline(meta,s"$dir/meta.hex")
  val phase=RegInit(0.U(3.W));val ip=RegInit(0.U(32.W));val op=RegInit(0.U(32.W));val mp=RegInit(0.U(32.W))
  val pending=RegInit(false.B);val ended=RegInit(false.B);val beat=Reg(new StreamBeat)
  val sawDesc=RegInit(false.B);val sawLast=RegInit(false.B)
  val random=RegInit("hc318725a".U(32.W));random:=Cat(random(30,0),random(31)^random(21)^random(1)^random(0))
  val cycles=RegInit(0.U(32.W));cycles:=cycles+1.U;assert(cycles<20000000.U,"PNG_EDGE_WATCHDOG")
  io.finished:=phase===4.U
  dut.io.cmd.valid:=phase===0.U;dut.io.cmd.bits:=0.U.asTypeOf(new PngCommand)
  dut.io.cmd.bits.tag:="he3000000".U+task;dut.io.cmd.bits.inputBytes:=field(13);dut.io.cmd.bits.outputLimit:=field(12)
  when(dut.io.cmd.fire){phase:=1.U;ip:=0.U;op:=0.U;mp:=0.U;pending:=false.B;ended:=false.B;sawDesc:=false.B;sawLast:=false.B}
  val normalK=Mux(field(4)-ip>=8.U,8.U,field(4)-ip)(3,0)
  val k=Mux(ip===0.U && field(14)===5.U,3.U,normalK)
  val bytes=Wire(Vec(8,UInt(8.W)))
  for(i<-0 until 8){bytes(i):=Mux(i.U<k,png((field(5)+ip+i.U)(math.max(1,log2Ceil(pn))-1,0)),0.U)}
  when(phase===1.U && ip<field(4) && !pending && !ended && field(11)=/=1.U && random(1,0)=/=0.U){
    pending:=true.B;beat.data:=bytes.asUInt;beat.keep:=((1.U(9.W)<<k)-1.U)(7,0);beat.last:=ip+k===field(4)
    when(ip===0.U && field(14)===1.U){beat.keep:=0.U;beat.last:=true.B}
    when(ip===0.U && field(14)===2.U){beat.keep:=5.U;beat.last:=true.B}
    when(ip===0.U && field(14)===3.U){beat.last:=true.B}
    when(field(14)===4.U){beat.last:=false.B}
  }
  dut.io.in.valid:=pending;dut.io.in.bits:=beat
  when(dut.io.in.fire){val next=ip+PopCount(beat.keep);ip:=next;pending:=false.B;when(beat.last || next>=field(13)){ended:=true.B}}
  dut.io.out.ready:=random(3,2)=/=0.U && field(15)=/=1.U
  dut.io.metadata.ready:=random(5,4)=/=0.U && field(15)=/=2.U
  dut.io.descriptor.ready:=random(7,6)=/=0.U && field(15)=/=3.U
  dut.io.done.ready:=random(9,8)=/=0.U
  val wasCancel=RegNext(cancel,false.B)
  for(port<-Seq(dut.io.out,dut.io.metadata)){
    val held=RegNext(port.valid && !port.ready,false.B);val prior=RegEnable(port.bits,port.valid)
    when(held && !cancel && !wasCancel){assert(port.valid && port.bits.asUInt===prior.asUInt,"PNG_EDGE_STREAM_STABILITY")}
  }
  val hi=RegNext(dut.io.descriptor.valid && !dut.io.descriptor.ready,false.B);val pi=RegEnable(dut.io.descriptor.bits,dut.io.descriptor.valid)
  when(hi && !cancel && !wasCancel){assert(dut.io.descriptor.valid && dut.io.descriptor.bits.asUInt===pi.asUInt,"PNG_EDGE_INFO_STABILITY")}
  val hd=RegNext(dut.io.done.valid && !dut.io.done.ready,false.B);val pd=RegEnable(dut.io.done.bits,dut.io.done.valid)
  when(hd && !cancel && !wasCancel){assert(dut.io.done.valid && dut.io.done.bits.asUInt===pd.asUInt,"PNG_EDGE_DONE_STABILITY")}
  when(dut.io.descriptor.fire){
    val d=dut.io.descriptor.bits
    assert(!sawDesc && d.tag==="he3000000".U+task && d.imageWidth===field(0) && d.imageHeight===field(1) && d.colorType===field(2) && d.bitDepth===field(3) && d.paletteBytes===field(8) && d.transparencyBytes===field(9),"PNG_EDGE_DESCRIPTOR")
    sawDesc:=true.B
  }
  when(dut.io.metadata.fire){
    val n=PopCount(dut.io.metadata.bits.keep)
    assert(sawDesc && n=/=0.U && mp+n<=field(8)+field(9) && dut.io.metadata.bits.keep===((1.U(9.W)<<n)-1.U)(7,0),"PNG_EDGE_META_KEEP")
    for(i<-0 until 8){when(i.U<n){
      val gold=meta((field(10)+mp+i.U)(math.max(1,log2Ceil(mn))-1,0))^Mux((negative=="meta").B && task===0.U && mp===0.U && i.U===0.U,1.U,0.U)
      assert(dut.io.metadata.bits.data(8*i+7,8*i)===gold,"PNG_EDGE_METADATA_MISMATCH")
    }}
    assert(dut.io.metadata.bits.last===(mp+n===field(8)+field(9)),"PNG_EDGE_META_LAST");mp:=mp+n
  }
  when(dut.io.out.fire){
    val n=PopCount(dut.io.out.bits.keep)
    assert(sawDesc && mp===field(8)+field(9) && n=/=0.U && !sawLast && op+n<=field(12) && dut.io.out.bits.keep===((1.U(9.W)<<n)-1.U)(7,0),"PNG_EDGE_CAPACITY_ORDER")
    when(field(16)=/=0.U){
      assert(op+n<=field(6),"PNG_EDGE_PREFIX_LENGTH")
      for(i<-0 until 8){when(i.U<n){
        val gold=raw((field(7)+op+i.U)(math.max(1,log2Ceil(rn))-1,0))^Mux((negative=="pixel").B && task===0.U && op===0.U && i.U===0.U,1.U,0.U)
        assert(dut.io.out.bits.data(8*i+7,8*i)===gold,"PNG_EDGE_BYTE_MISMATCH")
      }}
      assert(dut.io.out.bits.last===(op+n===field(6)),"PNG_EDGE_LAST")
    }
    op:=op+n;when(dut.io.out.bits.last){sawLast:=true.B}
  }
  def advance():Unit={
    printf(p"PNG_EDGE_CASE_PASS task=${task} status=${field(11)} bytes=${op}\n")
    task:=task+1.U;phase:=0.U;pending:=false.B
    when(task===(cases.size-1).U){phase:=4.U;printf(p"PNG_EDGE_SUITE_PASS\n")}
  }
  when(dut.io.done.fire){
    val wanted=field(11)^Mux((negative=="status").B && task===0.U,1.U,0.U)
    val wantedTag=("he3000000".U+task)^Mux((negative=="tag").B && task===0.U,1.U,0.U)
    when(dut.io.done.bits.status=/=wanted){printf(p"PNG_EDGE_STATUS_DETAIL task=${task} actual=${dut.io.done.bits.status} expected=${wanted} received=${ip}\n")}
    assert(field(15)===0.U && dut.io.done.bits.status===wanted,"PNG_EDGE_STATUS")
    assert(dut.io.done.bits.tag===wantedTag,"PNG_EDGE_TAG")
    assert(dut.io.done.bits.inputBytes===ip && dut.io.done.bits.outputBytes===op,"PNG_EDGE_COUNTS")
    assert(!dut.io.out.valid && !dut.io.metadata.valid && !dut.io.descriptor.valid,"PNG_EDGE_PENDING_AT_DONE")
    when(field(11)===0.U){assert(ip===field(13) && op===field(6) && sawLast && sawDesc && mp===field(8)+field(9),"PNG_EDGE_FINAL")}
    advance()
  }
  val abortReady=MuxLookup(field(15),false.B)(Seq(1.U->dut.io.out.valid,2.U->dut.io.metadata.valid,3.U->dut.io.descriptor.valid))
  when(phase===1.U && abortReady){cancel:=true.B;pending:=false.B;phase:=2.U}
  when(phase===2.U){assert(!dut.io.out.valid && !dut.io.metadata.valid && !dut.io.descriptor.valid && !dut.io.done.valid && dut.io.cmd.ready,"PNG_EDGE_RESET_CANCEL");phase:=3.U}
  when(phase===3.U){advance()}
}
object EmitDecoderEdges extends App {
  require(args.length==3);val out=Paths.get(args(0));require(!Files.exists(out));Files.createDirectories(out)
  val dir=Paths.get(args(1)).toAbsolutePath
  val cases=Files.readAllLines(dir.resolve("cases.txt")).asScala.filter(_.nonEmpty).map(_.split(" ").map(BigInt(_)).toSeq).toSeq
  ChiselStage.emitSystemVerilog(new DecoderEdgeSuite(cases,dir.toString,args(2)),
    firtoolOpts=Array("-O=release","-disable-all-randomization","-split-verilog",s"-o=$out"))
}
