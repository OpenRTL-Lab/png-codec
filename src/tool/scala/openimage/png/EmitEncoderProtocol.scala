// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: EmitEncoderProtocol.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import java.nio.file.{Files,Paths}
import scala.jdk.CollectionConverters._

class EncoderProtocolSuite(cases:Seq[Seq[BigInt]],beats:Seq[Seq[BigInt]],gold:Seq[Int],negative:String) extends Module {
  val io=IO(new Bundle{val finished=Output(Bool())})
  val cancel=WireDefault(false.B)
  val dut=withReset(reset.asBool || cancel){QualifiedCodec.encoder()}
  // W H type depth input limit plen tlen status received abort inN metaN inOff metaOff goldOff goldN
  val table=cases.transpose.map(v=>VecInit(v.map(_.U(32.W))))
  val data=VecInit(beats.map(_(0).U(64.W)));val keep=VecInit(beats.map(_(1).U(8.W)))
  val last=VecInit(beats.map(v=>(v(2)!=0).B));val expected=VecInit(gold.map(_.U(8.W)))
  val task=RegInit(0.U(math.max(1,log2Ceil(cases.length+1)).W))
  val index=task(math.max(1,log2Ceil(cases.length))-1,0)
  def field(n:Int)=table(n)(index)
  val phase=RegInit(0.U(3.W));val inputIndex=RegInit(0.U(16.W));val metaIndex=RegInit(0.U(16.W))
  val commandAge=RegInit(0.U(4.W))
  val inputPending=RegInit(false.B);val metaPending=RegInit(false.B)
  val outputBytes=RegInit(0.U(32.W));val sawLast=RegInit(false.B)
  val random=RegInit("hc13445a1".U(32.W));random:=Cat(random(30,0),random(31)^random(21)^random(1)^random(0))
  val cycles=RegInit(0.U(32.W));cycles:=cycles+1.U;assert(cycles<2000000.U,"PNG_PROTOCOL_WATCHDOG")
  val beatWidth=math.max(1,log2Ceil(beats.length))
  val inAt=(field(13)+inputIndex)(beatWidth-1,0);val metaAt=(field(14)+metaIndex)(beatWidth-1,0)
  io.finished:=phase===4.U
  dut.io.cmd.valid:=phase===0.U
  dut.io.cmd.bits.tag:="h79a00000".U+task
  dut.io.cmd.bits.imageWidth:=field(0);dut.io.cmd.bits.imageHeight:=field(1)
  dut.io.cmd.bits.colorType:=field(2);dut.io.cmd.bits.bitDepth:=field(3)
  dut.io.cmd.bits.inputBytes:=field(4);dut.io.cmd.bits.outputLimit:=field(5)
  dut.io.cmd.bits.filter:=0.U;dut.io.cmd.bits.fixed:=false.B
  dut.io.cmd.bits.paletteBytes:=field(6);dut.io.cmd.bits.transparencyBytes:=field(7)
  // Payload is deliberately destroyed immediately after handshake: only the
  // latched command may participate in staged validation and task execution.
  when(phase=/=0.U){dut.io.cmd.bits:=0.U.asTypeOf(new PngCommand)}
  when(phase===1.U && commandAge<15.U){commandAge:=commandAge+1.U}
  when(dut.io.cmd.fire){phase:=1.U;commandAge:=0.U;inputIndex:=0.U;metaIndex:=0.U;outputBytes:=0.U;sawLast:=false.B}
  when(phase===1.U && commandAge<4.U){
    assert(!dut.io.in.ready && !dut.io.metadata.ready && !dut.io.out.valid && !dut.io.done.valid,
      "PNG_PROTOCOL_PREPARE_ISOLATION")
  }
  when(phase===1.U && !inputPending && inputIndex<field(11) && metaIndex===field(12) && random(1,0)=/=0.U){inputPending:=true.B}
  when(phase===1.U && !metaPending && metaIndex<field(12) && random(3,2)=/=0.U){metaPending:=true.B}
  dut.io.in.valid:=inputPending;dut.io.in.bits.data:=data(inAt);dut.io.in.bits.keep:=keep(inAt);dut.io.in.bits.last:=last(inAt)
  dut.io.metadata.valid:=metaPending;dut.io.metadata.bits.data:=data(metaAt);dut.io.metadata.bits.keep:=keep(metaAt);dut.io.metadata.bits.last:=last(metaAt)
  when(dut.io.in.fire){inputIndex:=inputIndex+1.U;inputPending:=false.B}
  when(dut.io.metadata.fire){metaIndex:=metaIndex+1.U;metaPending:=false.B}
  dut.io.out.ready:=random(5,4)=/=0.U && field(10)===0.U
  dut.io.done.ready:=random(7,6)=/=0.U
  val stalled=RegNext(dut.io.out.valid && !dut.io.out.ready,false.B)
  val old=RegEnable(dut.io.out.bits,dut.io.out.valid)
  val wasCancel=RegNext(cancel,false.B)
  when(stalled && !cancel && !wasCancel){assert(dut.io.out.valid && dut.io.out.bits.asUInt===old.asUInt,"PNG_PROTOCOL_STABILITY")}
  when(dut.io.out.fire){
    val k=PopCount(dut.io.out.bits.keep)
    assert(outputBytes+k<=field(5) && k=/=0.U && !sawLast,"PNG_PROTOCOL_CAPACITY")
    when(field(8)===0.U){
      for(i<-0 until 8)when(dut.io.out.bits.keep(i)){
        val at=(field(15)+outputBytes+i.U)(math.max(1,log2Ceil(gold.length))-1,0)
        val answer=expected(at)^Mux((negative=="pixel").B && task===0.U && outputBytes+i.U===49.U,1.U,0.U)
        assert(dut.io.out.bits.data(8*i+7,8*i)===answer,"PNG_PROTOCOL_BYTE_MISMATCH")
      }
    }
    outputBytes:=outputBytes+k;when(dut.io.out.bits.last){sawLast:=true.B}
  }
  def advance():Unit={
    printf(p"PNG_PROTOCOL_CASE_PASS task=${task} status=${field(8)} output=${outputBytes}\n")
    task:=task+1.U;phase:=0.U
    when(task===(cases.length-1).U){phase:=4.U;printf(p"PNG_PROTOCOL_SUITE_PASS\n")}
  }
  when(dut.io.done.fire){
    val wantedStatus=field(8)^Mux((negative=="status").B && task===0.U,1.U,0.U)
    val wantedTag=("h79a00000".U+task)^Mux((negative=="tag").B && task===0.U,1.U,0.U)
    assert(field(10)===0.U && dut.io.done.bits.status===wantedStatus,"PNG_PROTOCOL_STATUS")
    assert(dut.io.done.bits.tag===wantedTag,"PNG_PROTOCOL_TAG")
    assert(dut.io.done.bits.inputBytes===field(9) && dut.io.done.bits.outputBytes===outputBytes,"PNG_PROTOCOL_LENGTH")
    assert(!dut.io.out.valid,"PNG_PROTOCOL_PENDING_AT_DONE")
    when(field(8)===0.U){assert(sawLast && outputBytes===field(16),"PNG_PROTOCOL_FINAL")}
    advance()
  }
  val cancelDuringPrepare=field(10)>=2.U && commandAge===field(10)-2.U
  when(phase===1.U && ((field(10)===1.U && dut.io.out.valid) || cancelDuringPrepare)){
    cancel:=true.B;inputPending:=false.B;metaPending:=false.B;phase:=2.U
  }
  when(phase===2.U){
    assert(!dut.io.out.valid && !dut.io.done.valid && dut.io.cmd.ready,"PNG_PROTOCOL_RESET_CANCEL")
    phase:=3.U
  }
  when(phase===3.U){advance()}
}
object EmitEncoderProtocol extends App {
  require(args.length==3)
  val out=Paths.get(args(0));require(!Files.exists(out));Files.createDirectories(out)
  val vectors=Paths.get(args(1))
  def rows(n:String)=Files.readAllLines(vectors.resolve(n)).asScala.filter(_.nonEmpty).map(_.split(" ").map(BigInt(_)).toSeq).toSeq
  val gold=Files.readAllLines(vectors.resolve("gold.txt")).asScala.filter(_.nonEmpty).map(_.toInt).toSeq
  ChiselStage.emitSystemVerilog(new EncoderProtocolSuite(rows("cases.txt"),rows("beats.txt"),gold,args(2)),
    firtoolOpts=Array("-O=release","-disable-all-randomization","-split-verilog",s"-o=$out"))
}
