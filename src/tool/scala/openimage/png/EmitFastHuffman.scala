// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: EmitFastHuffman.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import java.nio.file.{Files,Paths}
import scala.jdk.CollectionConverters._

class FastHuffmanSuite(cases:Seq[Seq[Int]],lengths:Seq[Int],bits:Seq[Int],symbols:Seq[Int],negative:Boolean,parallelLong:Boolean=false) extends Module {
  val io=IO(new Bundle{val finished=Output(Bool())})
  val dut=Module(new FastCanonicalHuffman(parallelLong=parallelLong))
  // symbols kind outcome lengthOffset bitN bitOffset symbolN symbolOffset ideal
  val table=cases.transpose.map(v=>VecInit(v.map(_.U(32.W))))
  val lens=VecInit(lengths.map(_.U(4.W)));val stream=VecInit(bits.map(x=>(x!=0).B));val gold=VecInit(symbols.map(_.U(9.W)))
  val task=RegInit(0.U(math.max(1,log2Ceil(cases.length+1)).W));val index=task(math.max(1,log2Ceil(cases.length))-1,0)
  def f(n:Int)=if(cases.length==1)table(n)(0) else table(n)(index)
  val phase=RegInit(0.U(3.W));val li=RegInit(0.U(32.W));val bi=RegInit(0.U(32.W));val si=RegInit(0.U(32.W))
  val avail=RegInit(0.U(5.W));val random=RegInit("h735ac381".U(32.W))
  random:=Cat(random(30,0),random(31)^random(21)^random(1)^random(0))
  val cycles=RegInit(0.U(32.W));cycles:=cycles+1.U;assert(cycles<1000000.U,"FAST_HUFFMAN_WATCHDOG")
  val lastFire=Reg(UInt(32.W));val firstFire=Reg(UInt(32.W))
  io.finished:=phase===4.U
  dut.io.config.valid:=phase===0.U;dut.io.config.bits.symbols:=f(0);dut.io.config.bits.kind:=f(1)
  when(dut.io.config.fire){phase:=1.U;li:=0.U;bi:=0.U;si:=0.U;avail:=0.U}
  dut.io.length.valid:=phase===1.U;dut.io.length.bits:=lens((f(3)+li)(math.max(1,log2Ceil(lengths.length))-1,0))
  when(dut.io.length.fire){li:=li+1.U;when(li+1.U===f(0)){phase:=2.U}}
  val peek=Wire(Vec(15,Bool()))
  for(i<-0 until 15){peek(i):=Mux(bi+i.U<f(4),stream((f(5)+bi+i.U)(math.max(1,log2Ceil(bits.length))-1,0)),false.B)}
  dut.io.query:=phase===3.U && (si<f(6) || f(2)===2.U)
  dut.io.bits:=peek.asUInt;dut.io.available:=avail
  dut.io.accept:=dut.io.valid && (f(8)=/=0.U || random(3,2)=/=0.U)
  when(phase===3.U){
    val grown=Mux(f(8)=/=0.U,15.U,avail+&Mux(random(1,0)=/=0.U,3.U,0.U))
    avail:=Mux(f(4)-bi<grown,f(4)-bi,Mux(grown>15.U,15.U,grown))
  }
  val held=RegNext(dut.io.valid && !dut.io.accept,false.B)
  val prior=RegEnable(Cat(dut.io.used,dut.io.symbol),dut.io.valid)
  when(held){assert(dut.io.valid && Cat(dut.io.used,dut.io.symbol)===prior,"FAST_HUFFMAN_STABILITY")}
  when(dut.io.accept){
    val wanted=gold((f(7)+si)(math.max(1,log2Ceil(symbols.length))-1,0))^Mux(negative.B && task===0.U && si===0.U,1.U,0.U)
    assert(f(2)===0.U && si<f(6) && dut.io.symbol===wanted,"FAST_HUFFMAN_SYMBOL_MISMATCH")
    assert(dut.io.used>=1.U && dut.io.used<=avail && bi+dut.io.used<=f(4),"FAST_HUFFMAN_BITS")
    when(f(8)=/=0.U && si=/=0.U){assert(cycles===lastFire+1.U,"FAST_HUFFMAN_CONTINUOUS_RATE")}
    when(si===0.U){firstFire:=cycles};lastFire:=cycles
    bi:=bi+dut.io.used;si:=si+1.U
    val left=f(4)-bi-dut.io.used
    avail:=Mux(f(8)=/=0.U,Mux(left>15.U,15.U,left),avail-dut.io.used)
  }
  def advance():Unit={
    printf(p"FAST_HUFFMAN_CASE_PASS task=${task} outcome=${f(2)} symbols=${si} bits=${bi} span=${lastFire-firstFire}\n")
    task:=task+1.U;phase:=0.U
    when(task===(cases.length-1).U){phase:=4.U;printf(p"FAST_HUFFMAN_SUITE_PASS\n")}
  }
  when(phase===2.U){
    when(dut.io.failed){assert(f(2)===1.U,"FAST_HUFFMAN_UNEXPECTED_BUILD_ERROR");advance()}
    .elsewhen(dut.io.built){assert(f(2)=/=1.U,"FAST_HUFFMAN_MISSED_BUILD_ERROR");phase:=3.U}
  }
  when(phase===3.U){
    when(dut.io.failed){assert(f(2)===2.U,"FAST_HUFFMAN_UNEXPECTED_DECODE_ERROR");advance()}
    .elsewhen(si===f(6) && bi===f(4)){
      assert(f(2)===0.U,"FAST_HUFFMAN_MISSED_DECODE_ERROR");advance()
    }
  }
}
object EmitFastHuffman extends App {
  require(args.length>=3 && args.length<=4);val out=Paths.get(args(0));require(!Files.exists(out));Files.createDirectories(out)
  val v=Paths.get(args(1))
  def values(n:String)=Files.readAllLines(v.resolve(n)).asScala.filter(_.nonEmpty).map(_.toInt).toSeq
  val cases=Files.readAllLines(v.resolve("cases.txt")).asScala.filter(_.nonEmpty).map(_.split(" ").map(_.toInt).toSeq).toSeq
  ChiselStage.emitSystemVerilog(new FastHuffmanSuite(cases,values("lengths.txt"),values("bits.txt"),values("symbols.txt"),args(2)=="negative",args.lift(3).contains("parallel")),
    firtoolOpts=Array("-O=release","-disable-all-randomization","-split-verilog",s"-o=$out"))
}
