// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: EmitZlib.scala
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

class ZlibSuite(cases: Seq[(Int,Int)], vector: String, wide: Boolean=false,
                continuous: Boolean=false) extends Module {
  val io=IO(new Bundle {val finished=Output(Bool())})
  val config=Wire(Decoupled(new ZlibConfig))
  val input=Wire(Decoupled(new ByteBeat))
  val output=Wire(Decoupled(new StreamBeat))
  if(wide) {
    val dut=Module(new WideZlibEncoder);val pack=Module(new WideBytePacker)
    dut.io.config<>config;dut.io.in<>input;pack.io.in<>dut.io.out
    output<>pack.io.out;pack.io.clear:=config.fire
    val stalled=RegNext(dut.io.out.valid && !dut.io.out.ready,false.B)
    val prior=RegEnable(dut.io.out.bits,dut.io.out.valid)
    when(stalled) { assert(dut.io.out.valid && dut.io.out.bits.asUInt===prior.asUInt,"ZLIB_STABILITY") }
  } else {
    val dut=Module(new ZlibEncoder);val pack=Module(new BytePacker)
    dut.io.config<>config;dut.io.in<>input;pack.io.in<>dut.io.out
    output<>pack.io.out;pack.io.clear:=false.B
    val stalled=RegNext(dut.io.out.valid && !dut.io.out.ready,false.B)
    val prior=RegEnable(dut.io.out.bits,dut.io.out.valid)
    when(stalled) { assert(dut.io.out.valid && dut.io.out.bits.asUInt===prior.asUInt,"ZLIB_STABILITY") }
  }
  val total=cases.last._1+cases.last._2
  val raw=Mem(total,UInt(8.W));loadMemoryFromFileInline(raw,vector)
  val lengths=VecInit(cases.map(_._1.U(32.W)))
  val offsets=VecInit(cases.map(_._2.U(32.W)))
  val task=RegInit(0.U(math.max(1,log2Ceil(cases.length*2+1)).W))
  val index=(task>>1)(math.max(1,log2Ceil(cases.length))-1,0)
  val phase=RegInit(0.U(2.W));val inIndex=RegInit(0.U(32.W))
  val outputBytes=RegInit(0.U(32.W));val pending=RegInit(false.B)
  val random=RegInit("hc1345617".U(32.W))
  random:=Cat(random(30,0),random(31)^random(21)^random(1)^random(0))
  val cycles=RegInit(0.U(32.W));cycles:=cycles+1.U
  val started=Reg(UInt(32.W));val firstInput=Reg(UInt(32.W))
  assert(cycles<20000000.U,"ZLIB_WATCHDOG")
  io.finished:=phase===2.U
  config.valid:=phase===0.U
  config.bits.inputBytes:=lengths(index);config.bits.fixed:=task(0)
  when(config.fire){phase:=1.U;inIndex:=0.U;outputBytes:=0.U;started:=cycles}
  when(phase===1.U && !pending && inIndex<lengths(index) && random(1,0)=/=0.U){pending:=true.B}
  input.valid:=(if(continuous) phase===1.U && inIndex<lengths(index) else pending)
  input.bits.data:=raw((offsets(index)+inIndex)(math.max(1,log2Ceil(total))-1,0))
  input.bits.last:=inIndex+1.U===lengths(index)
  when(input.fire){
    inIndex:=inIndex+1.U;pending:=false.B
    when(inIndex===0.U) { firstInput:=cycles }
    if(continuous) { when(inIndex=/=0.U && task(0)) { assert(cycles-firstInput===inIndex,"WIDE_ZLIB_INPUT_RATE") } }
  }
  output.ready:=(if(continuous) true.B else random(3,2)=/=0.U)
  when(output.fire){
    val k=PopCount(output.bits.keep)
    assert(k=/=0.U && output.bits.keep===((1.U(9.W)<<k)-1.U)(7,0),"ZLIB_KEEP")
    assert(outputBytes+k<=lengths(index)*2.U+32.U,"ZLIB_OUTPUT_BOUND")
    outputBytes:=outputBytes+k
    printf(p"ZLIB_DATA ${task} ${Hexadecimal(output.bits.data)} ${Hexadecimal(output.bits.keep)} ${output.bits.last}\n")
    when(output.bits.last){
      assert(inIndex===lengths(index),"ZLIB_ALL_INPUT")
      if(continuous) { assert(cycles-started<=lengths(index)+64.U,"WIDE_ZLIB_COMPLETE_RATE") }
      printf(p"ZLIB_RATE task=${task} raw=${lengths(index)} cycles=${cycles-started}\n")
      printf(p"ZLIB_CASE_PASS task=${task} bytes=${outputBytes+k}\n")
      task:=task+1.U;phase:=0.U
      when(task===(cases.length*2-1).U){printf(p"ZLIB_SUITE_PASS tasks=${task+1.U}\n");phase:=2.U}
    }
  }
}
object EmitZlib extends App {
  require(args.length>=2 && args.length<=4)
  val out=Paths.get(args(0));require(!Files.exists(out));Files.createDirectories(out)
  val vectors=Paths.get(args(1)).toAbsolutePath
  val cases=Files.readAllLines(vectors.resolve("cases.txt")).asScala.filter(_.nonEmpty).map { l=>
    val v=l.split(" ").map(_.toInt);(v(0),v(1))
  }.toSeq
  ChiselStage.emitSystemVerilog(new ZlibSuite(cases,vectors.resolve("raw.hex").toString,
      args.lift(2).contains("wide"),args.lift(3).contains("continuous")),
    firtoolOpts=Array("-O=release","-disable-all-randomization","-split-verilog",s"-o=$out"))
}
