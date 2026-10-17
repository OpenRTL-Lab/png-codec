// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: EmitDecoder.scala
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

class DecoderSuite(cases:Seq[Seq[Int]],pngFile:String,rawFile:String,metaFile:String,negative:Boolean) extends Module {
  val io=IO(new Bundle{val finished=Output(Bool())})
  val dut=QualifiedCodec.decoder()
  // W H type depth pngN pngOff rawN rawOff plen tlen metaOff
  val table=cases.transpose.map(v=>VecInit(v.map(_.U(32.W))))
  val task=RegInit(0.U(math.max(1,log2Ceil(cases.length+1)).W));val index=task(math.max(1,log2Ceil(cases.length))-1,0)
  def field(n:Int)=table(n)(index)
  val pn=cases.map(c=>c(4)+c(5)).max;val rn=cases.map(c=>c(6)+c(7)).max;val mn=math.max(1,cases.map(c=>c(8)+c(9)+c(10)).max)
  val png=Mem(pn,UInt(8.W));val raw=Mem(rn,UInt(8.W));val meta=Mem(mn,UInt(8.W))
  loadMemoryFromFileInline(png,pngFile);loadMemoryFromFileInline(raw,rawFile);loadMemoryFromFileInline(meta,metaFile)
  val phase=RegInit(0.U(2.W));val ip=RegInit(0.U(32.W));val op=RegInit(0.U(32.W));val mp=RegInit(0.U(32.W))
  val pending=RegInit(false.B);val beat=Reg(new StreamBeat);val sawDesc=RegInit(false.B);val sawLast=RegInit(false.B)
  val random=RegInit("h812a357e".U(32.W));random:=Cat(random(30,0),random(31)^random(21)^random(1)^random(0))
  val cycles=RegInit(0.U(32.W));cycles:=cycles+1.U;assert(cycles<100000000.U,"PNG_DECODER_WATCHDOG")
  io.finished:=phase===2.U;dut.io.cmd.valid:=phase===0.U;dut.io.cmd.bits:=0.U.asTypeOf(new PngCommand)
  dut.io.cmd.bits.tag:="hdc000000".U+task;dut.io.cmd.bits.inputBytes:=field(4);dut.io.cmd.bits.outputLimit:="h40000000".U
  when(dut.io.cmd.fire){ip:=0.U;op:=0.U;mp:=0.U;pending:=false.B;sawDesc:=false.B;sawLast:=false.B;phase:=1.U}
  val k=Mux(field(4)-ip>=8.U,8.U,field(4)-ip)(3,0);val bytes=Wire(Vec(8,UInt(8.W)))
  for(i<-0 until 8){bytes(i):=Mux(i.U<k,png((field(5)+ip+i.U)(math.max(1,log2Ceil(pn))-1,0)),0.U)}
  when(phase===1.U && ip<field(4) && !pending && random(1,0)=/=0.U){
    pending:=true.B;beat.data:=bytes.asUInt;beat.keep:=((1.U(9.W)<<k)-1.U)(7,0);beat.last:=ip+k===field(4)
  }
  dut.io.in.valid:=pending;dut.io.in.bits:=beat
  when(dut.io.in.fire){ip:=ip+PopCount(beat.keep);pending:=false.B}
  dut.io.out.ready:=random(3,2)=/=0.U;dut.io.metadata.ready:=random(5,4)=/=0.U
  dut.io.descriptor.ready:=random(7,6)=/=0.U;dut.io.done.ready:=random(9,8)=/=0.U
  for(port<-Seq(dut.io.out,dut.io.metadata)){
    val held=RegNext(port.valid && !port.ready,false.B);val prior=RegEnable(port.bits,port.valid)
    when(held){assert(port.valid && port.bits.asUInt===prior.asUInt,"PNG_DECODER_STREAM_STABILITY")}
  }
  val heldInfo=RegNext(dut.io.descriptor.valid && !dut.io.descriptor.ready,false.B);val priorInfo=RegEnable(dut.io.descriptor.bits,dut.io.descriptor.valid)
  when(heldInfo){assert(dut.io.descriptor.valid && dut.io.descriptor.bits.asUInt===priorInfo.asUInt,"PNG_DECODER_INFO_STABILITY")}
  when(dut.io.descriptor.fire){
    val d=dut.io.descriptor.bits
    assert(!sawDesc && d.tag==="hdc000000".U+task && d.imageWidth===field(0) && d.imageHeight===field(1) && d.colorType===field(2) && d.bitDepth===field(3) && d.paletteBytes===field(8) && d.transparencyBytes===field(9),"PNG_DECODER_DESCRIPTOR")
    sawDesc:=true.B
  }
  when(dut.io.metadata.fire){
    val n=PopCount(dut.io.metadata.bits.keep)
    assert(sawDesc && mp+n<=field(8)+field(9) && n=/=0.U && dut.io.metadata.bits.keep===((1.U(9.W)<<n)-1.U)(7,0),"PNG_DECODER_META_LENGTH")
    for(i<-0 until 8){when(i.U<n){assert(dut.io.metadata.bits.data(8*i+7,8*i)===meta((field(10)+mp+i.U)(math.max(1,log2Ceil(mn))-1,0)),"PNG_DECODER_METADATA")}}
    assert(dut.io.metadata.bits.last===(mp+n===field(8)+field(9)),"PNG_DECODER_META_LAST");mp:=mp+n
  }
  when(dut.io.out.fire){
    val n=PopCount(dut.io.out.bits.keep)
    assert(sawDesc && mp===field(8)+field(9) && !sawLast && op+n<=field(6) && n=/=0.U && dut.io.out.bits.keep===((1.U(9.W)<<n)-1.U)(7,0),"PNG_DECODER_ORDER_KEEP")
    for(i<-0 until 8){when(i.U<n){
      val gold=raw((field(7)+op+i.U)(math.max(1,log2Ceil(rn))-1,0))^Mux(negative.B && op===0.U && i.U===0.U,1.U,0.U)
      assert(dut.io.out.bits.data(8*i+7,8*i)===gold,"PNG_DECODER_BYTE_MISMATCH")
    }}
    assert(dut.io.out.bits.last===(op+n===field(6)),"PNG_DECODER_LAST");op:=op+n;when(dut.io.out.bits.last){sawLast:=true.B}
  }
  when(dut.io.done.fire){
    assert(dut.io.done.bits.status===0.U,"PNG_DECODER_STATUS")
    assert(dut.io.done.bits.tag==="hdc000000".U+task && dut.io.done.bits.inputBytes===field(4) && ip===field(4),"PNG_DECODER_COMPLETION_INPUT")
    assert(op===field(6) && dut.io.done.bits.outputBytes===op && sawLast && sawDesc && mp===field(8)+field(9),"PNG_DECODER_COMPLETION_OUTPUT")
    assert(dut.io.done.bits.pixels===field(0)*field(1),"PNG_DECODER_PIXEL_COUNT")
    printf(p"PNG_DECODER_CASE_PASS task=${task} bytes=${op} metadata=${mp}\n")
    task:=task+1.U;phase:=0.U;pending:=false.B
    when(task===(cases.length-1).U){phase:=2.U;printf(p"PNG_DECODER_SUITE_PASS\n")}
  }
}
object EmitDecoder extends App {
  require(args.length==3);val out=Paths.get(args(0));require(!Files.exists(out));Files.createDirectories(out)
  val v=Paths.get(args(1)).toAbsolutePath
  val cases=Files.readAllLines(v.resolve("cases.txt")).asScala.filter(_.nonEmpty).map(_.split(" ").map(_.toInt).toSeq).toSeq
  ChiselStage.emitSystemVerilog(new DecoderSuite(cases,v.resolve("png.hex").toString,v.resolve("raw.hex").toString,v.resolve("meta.hex").toString,args(2)=="negative"),
    firtoolOpts=Array("-O=release","-disable-all-randomization","-split-verilog",s"-o=$out"))
}
