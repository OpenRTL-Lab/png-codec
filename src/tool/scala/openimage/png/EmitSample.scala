// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: EmitSample.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import java.nio.file.{Files,Paths}
import scala.jdk.CollectionConverters._

/** 所有DUT协议驱动与检查均在这个验证环境，C++不提供像素答案。 */
class PngEncoderSample(raw: Seq[Int], golden: Seq[Int], width: Int, height: Int,
                       negative: Boolean) extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  val dut = Module(new PngStoredEncoder)
  val input = VecInit(raw.map(_.U(8.W)))
  val expected = VecInit(golden.zipWithIndex.map { case (b,i) =>
    (if (negative && i == golden.length/2) b ^ 1 else b).U(8.W)
  })
  val phase = RegInit(0.U(2.W))
  val inIndex = RegInit(0.U(32.W))
  val outIndex = RegInit(0.U(32.W))
  val pending = RegInit(false.B)
  val cycles = RegInit(0.U(32.W))
  val random = RegInit("h23586791".U(32.W))
  random := Cat(random(30,0),random(31)^random(21)^random(1)^random(0))
  cycles := cycles + 1.U
  assert(cycles < 1000000.U, "PNG_WATCHDOG")
  io.finished := phase === 2.U
  dut.io.cmd.valid := phase === 0.U
  dut.io.cmd.bits := 0.U.asTypeOf(new PngCommand)
  dut.io.cmd.bits.imageWidth := width.U
  dut.io.cmd.bits.imageHeight := height.U
  dut.io.cmd.bits.bitDepth := 8.U
  dut.io.cmd.bits.tag := "h504e4701".U
  dut.io.cmd.bits.inputBytes := raw.length.U
  dut.io.cmd.bits.outputLimit := golden.length.U
  when(dut.io.cmd.fire) { phase := 1.U }
  when(phase === 1.U && !pending && inIndex < raw.length.U && random(1,0) =/= 0.U) {
    pending := true.B
  }
  val left = raw.length.U - inIndex
  val count = Mux(left >= 8.U,8.U(4.W),left(3,0))
  val data = Wire(Vec(8,UInt(8.W)))
  for(i <- 0 until 8) data(i) := Mux(i.U < count,
    input((inIndex + i.U)(math.max(1,log2Ceil(raw.length))-1,0)),0.U)
  dut.io.in.valid := pending
  dut.io.in.bits.data := data.asUInt
  dut.io.in.bits.keep := ((1.U(9.W) << count) - 1.U)(7,0)
  dut.io.in.bits.last := left <= 8.U
  when(dut.io.in.fire) { inIndex := inIndex + count; pending := false.B }
  dut.io.out.ready := random(3,2) =/= 0.U
  dut.io.done.ready := random(5,4) =/= 0.U
  val stalled = RegNext(dut.io.out.valid && !dut.io.out.ready,false.B)
  val prior = RegEnable(dut.io.out.bits,dut.io.out.valid)
  when(stalled) { assert(dut.io.out.valid && dut.io.out.bits.asUInt === prior.asUInt,
    "PNG_OUTPUT_STABILITY") }
  when(dut.io.out.fire) {
    val k = PopCount(dut.io.out.bits.keep)
    assert(k =/= 0.U && dut.io.out.bits.keep === ((1.U(9.W) << k)-1.U)(7,0),"PNG_KEEP")
    assert(dut.io.out.bits.last === (outIndex + k === golden.length.U),"PNG_LAST")
    for(i <- 0 until 8) when(dut.io.out.bits.keep(i)) {
      assert(outIndex + i.U < golden.length.U,"PNG_OUTPUT_BOUND")
      assert(dut.io.out.bits.data(8*i+7,8*i) === expected((outIndex+i.U)(math.max(1,log2Ceil(golden.length))-1,0)),
        "PNG_BYTE_MISMATCH")
    }
    printf(p"PNG_DATA ${Hexadecimal(dut.io.out.bits.data)} ${Hexadecimal(dut.io.out.bits.keep)}\n")
    outIndex := outIndex + k
  }
  when(dut.io.done.fire) {
    assert(dut.io.done.bits.status === 0.U,"PNG_DONE_STATUS")
    assert(dut.io.done.bits.tag === "h504e4701".U,"PNG_TAG")
    assert(dut.io.done.bits.inputBytes === raw.length.U,"PNG_INPUT_COUNT")
    assert(dut.io.done.bits.outputBytes === golden.length.U,"PNG_OUTPUT_COUNT")
    assert(dut.io.done.bits.pixels === (width*height).U,"PNG_PIXELS")
    assert(outIndex === golden.length.U && inIndex === raw.length.U,"PNG_COMPLETE")
    printf(p"PNG_ENCODER_SAMPLE_PASS pixels=${dut.io.done.bits.pixels}\n")
    phase := 2.U
  }
}

object EmitSample extends App {
  require(args.length == 6,"输出目录/向量目录/宽/高/负控/仅产品")
  val out = Paths.get(args(0))
  require(!Files.exists(out),"不覆盖既有生成")
  Files.createDirectories(out)
  val vectors = Paths.get(args(1))
  def read(name: String) = Files.readAllLines(vectors.resolve(name)).asScala.map(Integer.parseInt(_,16)).toSeq
  val raw = read("input.hex")
  val gold = read("golden.hex")
  val width = args(2).toInt; val height=args(3).toInt
  require(raw.length == width*height && gold.length == (width+1)*height+68)
  ChiselStage.emitSystemVerilog(
    new PngEncoderSample(raw,gold,width,height,args(4)=="1"),
    firtoolOpts=Array("-O=release","-disable-all-randomization","-split-verilog",s"-o=$out"))
}
