// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: EmitHuffman.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import java.nio.file.{Files,Paths}
import scala.jdk.CollectionConverters._

class HuffmanSuite(cases:Seq[Seq[Int]],lengths:Seq[Int],bits:Seq[Int],symbols:Seq[Int],negative:Boolean) extends Module {
  val io=IO(new Bundle{val finished=Output(Bool())})
  val dut=Module(new CanonicalHuffman)
  // symbols kind outcome lengthOffset bitN bitOffset symbolN symbolOffset；outcome0正常1构树错2空树使用错
  val table=cases.transpose.map(v=>VecInit(v.map(_.U(16.W))))
  val lens=VecInit(lengths.map(_.U(4.W)));val stream=VecInit(bits.map(x=>(x!=0).B));val gold=VecInit(symbols.map(_.U(9.W)))
  val task=RegInit(0.U(math.max(1,log2Ceil(cases.length+1)).W));val index=task(math.max(1,log2Ceil(cases.length))-1,0)
  def field(n:Int)=table(n)(index)
  val phase=RegInit(0.U(3.W));val li=RegInit(0.U(16.W));val bi=RegInit(0.U(16.W));val si=RegInit(0.U(16.W))
  val pending=RegInit(false.B);val random=RegInit("h971ab432".U(32.W))
  random:=Cat(random(30,0),random(31)^random(21)^random(1)^random(0))
  val cycles=RegInit(0.U(32.W));cycles:=cycles+1.U;assert(cycles<100000.U,"HUFFMAN_WATCHDOG")
  io.finished:=phase===4.U
  dut.io.config.valid:=phase===0.U;dut.io.config.bits.symbols:=field(0);dut.io.config.bits.kind:=field(1)
  when(dut.io.config.fire){phase:=1.U;li:=0.U;bi:=0.U;si:=0.U;pending:=false.B}
  dut.io.length.valid:=phase===1.U
  dut.io.length.bits:=lens((field(3)+li)(math.max(1,log2Ceil(lengths.length))-1,0))
  when(dut.io.length.fire){li:=li+1.U;when(li+1.U===field(0)){phase:=2.U}}
  when(phase===3.U && bi<field(4) && random(1,0)=/=0.U){pending:=true.B}
  dut.io.bit.valid:=pending
  dut.io.bit.bits:=stream((field(5)+bi)(math.max(1,log2Ceil(bits.length))-1,0))
  when(dut.io.bit.fire){bi:=bi+1.U;pending:=false.B}
  dut.io.symbol.ready:=random(3,2)=/=0.U
  val stalled=RegNext(dut.io.symbol.valid && !dut.io.symbol.ready,false.B);val prior=RegEnable(dut.io.symbol.bits,dut.io.symbol.valid)
  when(stalled){assert(dut.io.symbol.valid && dut.io.symbol.bits===prior,"HUFFMAN_STABILITY")}
  when(dut.io.symbol.fire){
    val wanted=gold((field(7)+si)(math.max(1,log2Ceil(symbols.length))-1,0))^Mux(negative.B && task===0.U && si===0.U,1.U,0.U)
    assert(field(2)===0.U && si<field(6) && dut.io.symbol.bits===wanted,"HUFFMAN_SYMBOL_MISMATCH")
    si:=si+1.U
  }
  def advance():Unit={
    task:=task+1.U;phase:=0.U;pending:=false.B
    printf(p"HUFFMAN_CASE_PASS task=${task} outcome=${field(2)}\n")
    when(task===(cases.length-1).U){phase:=4.U;printf(p"HUFFMAN_SUITE_PASS\n")}
  }
  when(phase===2.U){
    when(dut.io.failed){assert(field(2)===1.U,"HUFFMAN_UNEXPECTED_BUILD_ERROR");advance()}
    .elsewhen(dut.io.built){assert(field(2)=/=1.U,"HUFFMAN_MISSED_BUILD_ERROR");phase:=3.U}
  }
  when(phase===3.U){
    when(dut.io.failed){assert(field(2)===2.U,"HUFFMAN_UNEXPECTED_DECODE_ERROR");advance()}
    .elsewhen(si===field(6) && bi===field(4) && !pending){assert(field(2)===0.U,"HUFFMAN_MISSED_DECODE_ERROR");advance()}
  }
}
object EmitHuffman extends App {
  require(args.length==3);val out=Paths.get(args(0));require(!Files.exists(out));Files.createDirectories(out)
  val v=Paths.get(args(1))
  def values(n:String)=Files.readAllLines(v.resolve(n)).asScala.filter(_.nonEmpty).map(_.toInt).toSeq
  val cases=Files.readAllLines(v.resolve("cases.txt")).asScala.filter(_.nonEmpty).map(_.split(" ").map(_.toInt).toSeq).toSeq
  ChiselStage.emitSystemVerilog(new HuffmanSuite(cases,values("lengths.txt"),values("bits.txt"),values("symbols.txt"),args(2)=="negative"),
    firtoolOpts=Array("-O=release","-disable-all-randomization","-split-verilog",s"-o=$out"))
}
