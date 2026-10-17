// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: PngStoredEncoder.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._

/** 阶段B的完整Gray8/None/STORED码流；单个IDAT内filtered<=32757。
  * 该局部产品明确不代表尚未实现的完整PNG顶层。 */
class PngStoredEncoder extends Module {
  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new PngCommand))
    val in = Flipped(Decoupled(new StreamBeat))
    val out = Decoupled(new StreamBeat)
    val done = Decoupled(new PngResult)
  })
  val idle :: encoding :: drain :: finish :: Nil = Enum(4)
  val state = RegInit(idle)
  val cmd = Reg(new PngCommand)
  val status = RegInit(0.U(3.W))
  val n = Reg(UInt(32.W))
  val total = Reg(UInt(32.W))
  val pos = RegInit(0.U(32.W))
  val rowPos = RegInit(0.U(14.W))
  val inputCount = RegInit(0.U(32.W))
  val outputCount = RegInit(0.U(32.W))
  val inputClosed = RegInit(false.B)
  val held = RegInit(0.U(64.W))
  val heldCount = RegInit(0.U(4.W))
  val crcHeader = RegInit("hffffffff".U(32.W))
  val crcData = RegInit("hffffffff".U(32.W))
  val s1 = RegInit(1.U(16.W))
  val s2 = RegInit(0.U(16.W))
  val pack = Module(new BytePacker)
  io.out <> pack.io.out
  pack.io.clear := false.B
  pack.io.in.valid := false.B
  pack.io.in.bits.data := 0.U
  pack.io.in.bits.last := pos === total - 1.U
  io.cmd.ready := state === idle
  io.in.ready := (state === encoding && heldCount === 0.U && !inputClosed) ||
    (state === drain && !inputClosed)
  io.done.valid := state === finish
  io.done.bits.tag := cmd.tag
  io.done.bits.status := status
  io.done.bits.inputBytes := inputCount
  io.done.bits.outputBytes := outputCount
  io.done.bits.pixels := Mux(status === 0.U, cmd.imageWidth * cmd.imageHeight, 0.U)

  when(io.cmd.fire) {
    cmd := io.cmd.bits
    val c = io.cmd.bits
    val pixels = c.imageWidth * c.imageHeight
    val filtered = (c.imageWidth +& 1.U) * c.imageHeight
    n := filtered
    total := filtered + 68.U
    pos := 0.U; rowPos := 0.U; inputCount := 0.U; outputCount := 0.U
    inputClosed := false.B; heldCount := 0.U; held := 0.U
    crcHeader := "hffffffff".U; crcData := "hffffffff".U
    s1 := 1.U; s2 := 0.U; status := 0.U
    state := encoding
    when(c.imageWidth === 0.U || c.imageWidth > 8192.U || c.imageHeight === 0.U || c.imageHeight > 8192.U ||
      c.inputBytes =/= pixels || c.outputLimit === 0.U || c.outputLimit > "h40000000".U) {
      status := 1.U; state := finish; inputClosed := true.B
    }.elsewhen(c.colorType =/= 0.U || c.bitDepth =/= 8.U || c.filter =/= 0.U || c.fixed ||
      c.paletteBytes =/= 0.U || c.transparencyBytes =/= 0.U || filtered > 32757.U) {
      status := 2.U; state := drain
    }.elsewhen(c.outputLimit < filtered + 68.U) {
      status := 6.U; state := drain
    }
  }

  when(io.in.fire) {
    val k = PopCount(io.in.bits.keep)
    val next = inputCount +& k
    val mask = ((1.U(9.W) << k) - 1.U)(7,0)
    val bad = k === 0.U || io.in.bits.keep =/= mask ||
      (!io.in.bits.last && k =/= 8.U) || next > cmd.inputBytes ||
      (io.in.bits.last =/= (next === cmd.inputBytes))
    inputCount := next
    when(io.in.bits.last || next >= cmd.inputBytes) { inputClosed := true.B }
    when(state === encoding) {
      held := io.in.bits.data; heldCount := k
      when(bad) { status := 5.U; state := drain; heldCount := 0.U }
    }
  }
  when(io.out.fire) { outputCount := outputCount + PopCount(io.out.bits.keep) }

  def beByte(v: UInt, i: UInt): UInt = (v >> ((3.U - i) << 3))(7,0)
  val signature = VecInit(Seq(0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a).map(_.U(8.W)))
  val ihdrType = VecInit("IHDR".map(_.toInt.U(8.W)).toSeq)
  val idatType = VecInit("IDAT".map(_.toInt.U(8.W)).toSeq)
  val iend = VecInit(Seq(0,0,0,0,0x49,0x45,0x4e,0x44,0xae,0x42,0x60,0x82).map(_.U(8.W)))
  val payload = pos >= 48.U && pos < n + 48.U
  val needRaw = payload && rowPos =/= 0.U
  val byte = WireDefault(0.U(8.W))
  when(pos < 8.U) { byte := signature(pos(2,0)) }
  .elsewhen(pos < 12.U) { byte := Mux(pos === 11.U,13.U,0.U) }
  .elsewhen(pos < 16.U) { byte := ihdrType((pos - 12.U)(1,0)) }
  .elsewhen(pos < 20.U) { byte := beByte(cmd.imageWidth.pad(32),(pos - 16.U)(1,0)) }
  .elsewhen(pos < 24.U) { byte := beByte(cmd.imageHeight.pad(32),(pos - 20.U)(1,0)) }
  .elsewhen(pos < 29.U) { byte := Mux(pos === 24.U,8.U,0.U) }
  .elsewhen(pos < 33.U) { byte := beByte(~crcHeader,(pos - 29.U)(1,0)) }
  .elsewhen(pos < 37.U) { byte := beByte(n + 11.U,(pos - 33.U)(1,0)) }
  .elsewhen(pos < 41.U) { byte := idatType((pos - 37.U)(1,0)) }
  .elsewhen(pos === 41.U) { byte := "h78".U }
  .elsewhen(pos === 42.U || pos === 43.U) { byte := 1.U }
  .elsewhen(pos === 44.U) { byte := n(7,0) }
  .elsewhen(pos === 45.U) { byte := n(15,8) }
  .elsewhen(pos === 46.U) { byte := ~n(7,0) }
  .elsewhen(pos === 47.U) { byte := ~n(15,8) }
  .elsewhen(payload) { byte := Mux(rowPos === 0.U,0.U,held(7,0)) }
  .elsewhen(pos < n + 52.U) { byte := beByte(Cat(s2,s1),(pos - n - 48.U)(1,0)) }
  .elsewhen(pos < n + 56.U) { byte := beByte(~crcData,(pos - n - 52.U)(1,0)) }
  .otherwise { byte := iend((pos - n - 56.U)(3,0)) }

  when(state === encoding && pos < total) {
    pack.io.in.valid := !needRaw || heldCount =/= 0.U
    pack.io.in.bits.data := byte
    when(pack.io.in.fire) {
      pos := pos + 1.U
      when(pos >= 12.U && pos < 29.U) { crcHeader := Checksums.crc32(crcHeader,byte) }
      when(pos >= 37.U && pos < n + 52.U) { crcData := Checksums.crc32(crcData,byte) }
      when(payload) {
        val (a,b) = Checksums.adlerNext(s1,s2,byte); s1 := a; s2 := b
        rowPos := Mux(rowPos === cmd.imageWidth,0.U,rowPos + 1.U)
        when(needRaw) { held := held >> 8; heldCount := heldCount - 1.U }
      }
    }
  }
  when(state === encoding && pos === total && pack.io.empty && inputClosed) { state := finish }
  // 出错时已置valid的完整拍必须继续交付；尚未置valid的半拍可取消。
  when(state === drain && inputClosed && !pack.io.out.valid) {
    pack.io.clear := true.B; state := finish
  }
  when(io.done.fire) { state := idle }
}
