// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: EmitInflate.scala
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

class InflateSuite(cases:Seq[Seq[Int]],encodedFile:String,rawFile:String,negative:Boolean) extends Module {
  val io=IO(new Bundle{val finished=Output(Bool())})
  val dut=Module(new Inflate)
  // compressedN compressedOff expectedN rawOff status configN；错误用例只比已交付前缀。
  val table=cases.transpose.map(v=>VecInit(v.map(_.U(32.W))))
  val task=RegInit(0.U(math.max(1,log2Ceil(cases.length+1)).W));val index=task(math.max(1,log2Ceil(cases.length))-1,0)
  def field(n:Int)=if(cases.length==1)table(n)(0) else table(n)(index)
  val zn=cases.map(c=>c(0)+c(1)).max;val rn=math.max(2,cases.map(c=>c(2)+c(3)).max)
  val encoded=Mem(zn,UInt(8.W));loadMemoryFromFileInline(encoded,encodedFile)
  val raw=Mem(rn,UInt(8.W));loadMemoryFromFileInline(raw,rawFile)
  val phase=RegInit(0.U(2.W));val ip=RegInit(0.U(32.W));val op=RegInit(0.U(32.W))
  val pending=RegInit(false.B);val random=RegInit("h39176b28".U(32.W))
  random:=Cat(random(30,0),random(31)^random(21)^random(1)^random(0))
  val cycles=RegInit(0.U(32.W));cycles:=cycles+1.U;assert(cycles<30000000.U,"INFLATE_WATCHDOG")
  io.finished:=phase===2.U
  dut.io.config.valid:=phase===0.U;dut.io.config.bits.outputBytes:=field(5)
  when(dut.io.config.fire){ip:=0.U;op:=0.U;pending:=false.B;phase:=1.U}
  when(phase===1.U && ip<field(0) && !pending && random(1,0)=/=0.U){pending:=true.B}
  dut.io.in.valid:=pending
  dut.io.in.bits:=encoded((field(1)+ip)(math.max(1,log2Ceil(zn))-1,0))
  dut.io.inputEnded:=ip===field(0)
  when(dut.io.in.fire){ip:=ip+1.U;pending:=false.B}
  dut.io.out.ready:=random(3,2)=/=0.U;dut.io.done.ready:=random(5,4)=/=0.U
  val held=RegNext(dut.io.out.valid && !dut.io.out.ready,false.B)
  val prior=RegEnable(dut.io.out.bits,dut.io.out.valid)
  when(held){assert(dut.io.out.valid && dut.io.out.bits.asUInt===prior.asUInt,"INFLATE_STABILITY")}
  val doneHeld=RegNext(dut.io.done.valid && !dut.io.done.ready,false.B);val priorDone=RegEnable(dut.io.done.bits,dut.io.done.valid)
  when(doneHeld){assert(dut.io.done.valid && dut.io.done.bits===priorDone,"INFLATE_DONE_STABILITY")}
  when(dut.io.out.fire){
    val expected=if(rn==1)raw(0.U) else raw((field(3)+op)(math.max(1,log2Ceil(rn))-1,0))
    val gold=expected^Mux(negative.B && field(2)=/=0.U && op===0.U,1.U,0.U)
    assert(op<field(2) && dut.io.out.bits.data===gold,"INFLATE_BYTE_MISMATCH")
    assert(dut.io.out.bits.last===(op+1.U===field(5)),"INFLATE_LAST")
    op:=op+1.U
  }
  when(dut.io.done.fire){
    assert(dut.io.done.bits===field(4),"INFLATE_STATUS")
    when(field(4)===0.U){assert(op===field(2) && ip===field(0),"INFLATE_LENGTH")}
    assert(op===dut.io.outputBytes && ip===dut.io.inputBytes,"INFLATE_COUNTS")
    printf(p"INFLATE_CASE_PASS task=${task} bytes=${op} input=${ip} status=${dut.io.done.bits}\n")
    task:=task+1.U;phase:=0.U;pending:=false.B
    when(task===(cases.length-1).U){phase:=2.U;printf(p"INFLATE_SUITE_PASS\n")}
  }
}
object EmitInflate extends App {
  require(args.length>=3 && args.length<=5);val out=Paths.get(args(0));require(!Files.exists(out));Files.createDirectories(out)
  val v=Paths.get(args(1)).toAbsolutePath
  val cases=Files.readAllLines(v.resolve("cases.txt")).asScala.filter(_.nonEmpty).map(_.split(" ").map(_.toInt).toSeq).toSeq
  def suite=if(args.lift(3).contains("fast"))
    new FastInflateSuite(cases,v.resolve("encoded.hex").toString,v.resolve("raw.hex").toString,args(2)=="negative",args.lift(4).contains("continuous"))
    else new InflateSuite(cases,v.resolve("encoded.hex").toString,v.resolve("raw.hex").toString,args(2)=="negative")
  ChiselStage.emitSystemVerilog(suite,
    firtoolOpts=Array("-O=release","-disable-all-randomization","-split-verilog",s"-o=$out"))
}
