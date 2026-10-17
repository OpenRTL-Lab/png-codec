// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: EmitRows.scala
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

case class RowCase(rowBytes: Int, rows: Int, bpp: Int, offset: Int) {
  def length: Int = rowBytes*rows
}
class RowTransformSuite(cases: Seq[RowCase], totalBytes: Int, vectorDir: String,
                        negative: Boolean, continuous: Boolean = false) extends Module {
  val io = IO(new Bundle { val finished = Output(Bool()) })
  val dut = Module(new PngRowTransform)
  val raw = Mem(totalBytes,UInt(8.W))
  val filtered = Mem(totalBytes,UInt(8.W))
  val filters = Mem(totalBytes,UInt(8.W))
  loadMemoryFromFileInline(raw,s"$vectorDir/raw.hex")
  loadMemoryFromFileInline(filtered,s"$vectorDir/filtered.hex")
  loadMemoryFromFileInline(filters,s"$vectorDir/filters.hex")
  val rowBytes = VecInit(cases.map(_.rowBytes.U(17.W)))
  val rows = VecInit(cases.map(_.rows.U(14.W)))
  val bpps = VecInit(cases.map(_.bpp.U(4.W)))
  val offsets = VecInit(cases.map(_.offset.U(32.W)))
  val lengths = VecInit(cases.map(_.length.U(32.W)))
  val task = RegInit(0.U(math.max(1,log2Ceil(cases.length*2+1)).W))
  val index = (task >> 1)(math.max(1,log2Ceil(cases.length))-1,0)
  val inverse = task(0)
  val phase = RegInit(0.U(2.W))
  val inIndex = RegInit(0.U(32.W))
  val outIndex = RegInit(0.U(32.W))
  val pending = RegInit(false.B)
  val random = RegInit("h87345169".U(32.W))
  random := Cat(random(30,0),random(31)^random(21)^random(1)^random(0))
  val cycles = RegInit(0.U(32.W))
  val started = Reg(UInt(32.W))
  cycles := cycles + 1.U
  assert(cycles < 20000000.U,"ROW_WATCHDOG")
  io.finished := phase === 2.U
  dut.io.config.valid := phase === 0.U
  dut.io.config.bits.rowBytes := rowBytes(index)
  dut.io.config.bits.rows := rows(index)
  dut.io.config.bits.bytesPerPixel := bpps(index)
  dut.io.config.bits.inverse := inverse
  when(dut.io.config.fire) { phase := 1.U; inIndex := 0.U; outIndex := 0.U; started := cycles }
  when(phase === 1.U && !pending && inIndex < lengths(index) && random(1,0) =/= 0.U) {
    pending := true.B
  }
  val addressBits = math.max(1,log2Ceil(totalBytes))
  val inAddr = (offsets(index)+inIndex)(addressBits-1,0)
  val outAddr = (offsets(index)+outIndex)(addressBits-1,0)
  dut.io.in.valid := (if(continuous) phase===1.U && inIndex<lengths(index) else pending)
  dut.io.in.bits.data := Mux(inverse,filtered(inAddr),raw(inAddr))
  dut.io.in.bits.filter := filters(inAddr)(2,0)
  when(dut.io.in.fire) { pending := false.B; inIndex := inIndex + 1.U }
  dut.io.out.ready := (if(continuous) true.B else random(3,2) =/= 0.U)
  val stalled = RegNext(dut.io.out.valid && !dut.io.out.ready,false.B)
  val prior = RegEnable(dut.io.out.bits,dut.io.out.valid)
  when(stalled) { assert(dut.io.out.valid && dut.io.out.bits.asUInt === prior.asUInt,"ROW_STABILITY") }
  val expected = Mux(inverse,raw(outAddr),filtered(outAddr))
  val corrupt = if(negative) task === 0.U && outIndex === 5.U else false.B
  when(dut.io.out.fire) {
    assert(outIndex < lengths(index),"ROW_OUTPUT_BOUND")
    assert(dut.io.out.bits.data === (expected ^ corrupt.asUInt),"ROW_BYTE_MISMATCH")
    assert(dut.io.out.bits.last === (outIndex + 1.U === lengths(index)),"ROW_LAST")
    outIndex := outIndex + 1.U
    when(dut.io.out.bits.last) {
      assert(inIndex === lengths(index),"ROW_INPUT_COMPLETE")
      if(continuous) {
        assert(cycles-started===lengths(index)+1.U,"ROW_CONTINUOUS_RATE")
        printf(p"ROW_RATE_EVENT task=${task} bytes=${lengths(index)} cycles=${cycles-started}\n")
      }
      printf(p"ROW_CASE_PASS case=${task} inverse=${inverse} bytes=${lengths(index)}\n")
      task := task + 1.U; phase := 0.U
      when(task === (cases.length*2-1).U) {
        printf(p"ROW_SUITE_PASS tasks=${task+1.U}\n");phase := 2.U
      }
    }
  }
}
object EmitRows extends App {
  require(args.length == 3 || args.length == 4,"输出目录/向量目录/负控/连续")
  val out = Paths.get(args(0)); require(!Files.exists(out));Files.createDirectories(out)
  val vectors = Paths.get(args(1)).toAbsolutePath
  val lines = Files.readAllLines(vectors.resolve("cases.txt")).asScala
  val cases = lines.filter(_.nonEmpty).map { line =>
    val v=line.split(" ").map(_.toInt);RowCase(v(0),v(1),v(2),v(3))
  }.toSeq
  require(cases.nonEmpty)
  val total=cases.last.offset+cases.last.length
  ChiselStage.emitSystemVerilog(new RowTransformSuite(cases,total,vectors.toString,args(2)=="1",args.lift(3).contains("continuous")),
    firtoolOpts=Array("-O=release","-disable-all-randomization","-split-verilog",s"-o=$out"))
}
