// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: EmitEncoder.scala
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

class EncoderSuite(cases:Seq[Seq[Int]],rawFile:String,metaFile:String) extends Module {
  val io=IO(new Bundle{val finished=Output(Bool())})
  val dut=QualifiedCodec.encoder()
  // 列：W H type depth filter fixed rawN rawOff plen tlen metaOff
  val table=cases.transpose.map(v=>VecInit(v.map(_.U(32.W))))
  val task=RegInit(0.U(math.max(1,log2Ceil(cases.length+1)).W))
  val index=task(math.max(1,log2Ceil(cases.length))-1,0)
  def field(n:Int)=table(n)(index)
  val rawN=cases.last(6)+cases.last(7);val metaN=cases.last(8)+cases.last(9)+cases.last(10)
  val raw=Mem(rawN,UInt(8.W));loadMemoryFromFileInline(raw,rawFile)
  val meta=Mem(math.max(1,metaN),UInt(8.W));loadMemoryFromFileInline(meta,metaFile)
  val phase=RegInit(0.U(2.W));val inPos=RegInit(0.U(32.W));val metaPos=RegInit(0.U(32.W))
  val inPending=RegInit(false.B);val metaPending=RegInit(false.B)
  val inBeat=Reg(new StreamBeat);val metaBeat=Reg(new StreamBeat)
  val outBytes=RegInit(0.U(32.W));val sawLast=RegInit(false.B)
  val random=RegInit("h659ba152".U(32.W));random:=Cat(random(30,0),random(31)^random(21)^random(1)^random(0))
  val cycles=RegInit(0.U(32.W));cycles:=cycles+1.U
  assert(cycles<50000000.U,"PNG_ENCODER_WATCHDOG")
  io.finished:=phase===2.U
  dut.io.cmd.valid:=phase===0.U
  dut.io.cmd.bits.tag:="ha5000000".U+task
  dut.io.cmd.bits.inputBytes:=field(6);dut.io.cmd.bits.outputLimit:="h40000000".U
  dut.io.cmd.bits.imageWidth:=field(0);dut.io.cmd.bits.imageHeight:=field(1)
  dut.io.cmd.bits.colorType:=field(2);dut.io.cmd.bits.bitDepth:=field(3)
  dut.io.cmd.bits.filter:=field(4);dut.io.cmd.bits.fixed:=field(5)(0)
  dut.io.cmd.bits.paletteBytes:=field(8);dut.io.cmd.bits.transparencyBytes:=field(9)
  when(dut.io.cmd.fire){phase:=1.U;inPos:=0.U;metaPos:=0.U;outBytes:=0.U;sawLast:=false.B}
  val metaLength=field(8)+field(9)
  val inTake=Mux(field(6)-inPos>=8.U,8.U,field(6)-inPos)(3,0)
  val metaTake=Mux(metaLength-metaPos>=8.U,8.U,metaLength-metaPos)(3,0)
  val inData=Wire(Vec(8,UInt(8.W)));val metaData=Wire(Vec(8,UInt(8.W)))
  for(i<-0 until 8){
    inData(i):=Mux(i.U<inTake,raw((field(7)+inPos+i.U)(math.max(1,log2Ceil(rawN))-1,0)),0.U)
    metaData(i):=Mux(i.U<metaTake,meta((field(10)+metaPos+i.U)(math.max(1,log2Ceil(math.max(1,metaN)))-1,0)),0.U)
  }
  when(phase===1.U && !inPending && inPos<field(6) && metaPos===metaLength && random(1,0)=/=0.U){
    inPending:=true.B;inBeat.data:=inData.asUInt;inBeat.keep:=((1.U(9.W)<<inTake)-1.U)(7,0);inBeat.last:=inPos+inTake===field(6)
  }
  when(phase===1.U && !metaPending && metaPos<metaLength && random(3,2)=/=0.U){
    metaPending:=true.B;metaBeat.data:=metaData.asUInt;metaBeat.keep:=((1.U(9.W)<<metaTake)-1.U)(7,0);metaBeat.last:=metaPos+metaTake===metaLength
  }
  dut.io.in.valid:=inPending;dut.io.in.bits:=inBeat
  dut.io.metadata.valid:=metaPending;dut.io.metadata.bits:=metaBeat
  when(dut.io.in.fire){inPos:=inPos+PopCount(inBeat.keep);inPending:=false.B}
  when(dut.io.metadata.fire){metaPos:=metaPos+PopCount(metaBeat.keep);metaPending:=false.B}
  dut.io.out.ready:=random(5,4)=/=0.U;dut.io.done.ready:=random(7,6)=/=0.U
  val stalled=RegNext(dut.io.out.valid && !dut.io.out.ready,false.B)
  val prior=RegEnable(dut.io.out.bits,dut.io.out.valid)
  when(stalled){assert(dut.io.out.valid && dut.io.out.bits.asUInt===prior.asUInt,"PNG_ENCODER_STABILITY")}
  val doneStall=RegNext(dut.io.done.valid && !dut.io.done.ready,false.B)
  val priorDone=RegEnable(dut.io.done.bits,dut.io.done.valid)
  when(doneStall){assert(dut.io.done.valid && dut.io.done.bits.asUInt===priorDone.asUInt,"PNG_DONE_STABILITY")}
  when(dut.io.out.fire){
    val k=PopCount(dut.io.out.bits.keep)
    assert(!sawLast && k=/=0.U && dut.io.out.bits.keep===((1.U(9.W)<<k)-1.U)(7,0),"PNG_ENCODER_KEEP")
    assert(dut.io.out.bits.last || k===8.U,"PNG_ENCODER_NONFINAL_KEEP")
    outBytes:=outBytes+k;when(dut.io.out.bits.last){sawLast:=true.B}
    printf(p"PNG_DATA ${task} ${Hexadecimal(dut.io.out.bits.data)} ${Hexadecimal(dut.io.out.bits.keep)} ${dut.io.out.bits.last}\n")
  }
  when(dut.io.done.fire){
    assert(dut.io.done.bits.status===0.U,"PNG_ENCODER_STATUS")
    assert(dut.io.done.bits.tag==="ha5000000".U+task,"PNG_ENCODER_TAG")
    assert(dut.io.done.bits.inputBytes===field(6) && inPos===field(6),"PNG_ENCODER_INPUT")
    assert(dut.io.done.bits.outputBytes===outBytes && sawLast,"PNG_ENCODER_OUTPUT")
    assert(dut.io.done.bits.imageWidth===field(0) && dut.io.done.bits.imageHeight===field(1) &&
      dut.io.done.bits.colorType===field(2) && dut.io.done.bits.bitDepth===field(3) &&
      dut.io.done.bits.pixels===field(0)*field(1),"PNG_ENCODER_DESCRIPTOR")
    printf(p"PNG_ENCODER_CASE_PASS task=${task} bytes=${outBytes}\n")
    task:=task+1.U;phase:=0.U
    when(task===(cases.length-1).U){phase:=2.U;printf(p"PNG_ENCODER_SUITE_PASS\n")}
  }
}
object EmitEncoder extends App {
  require(args.length==2)
  val out=Paths.get(args(0));require(!Files.exists(out));Files.createDirectories(out)
  val v=Paths.get(args(1)).toAbsolutePath
  val cases=Files.readAllLines(v.resolve("cases.txt")).asScala.filter(_.nonEmpty).map(_.split(" ").map(_.toInt).toSeq).toSeq
  ChiselStage.emitSystemVerilog(new EncoderSuite(cases,v.resolve("raw.hex").toString,v.resolve("meta.hex").toString),
    firtoolOpts=Array("-O=release","-disable-all-randomization","-split-verilog",s"-o=$out"))
}
