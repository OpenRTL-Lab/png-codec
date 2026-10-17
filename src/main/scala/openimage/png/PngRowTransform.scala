// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: PngRowTransform.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._

class RowConfig extends Bundle {
  val rowBytes = UInt(17.W)
  val rows = UInt(14.W)
  val bytesPerPixel = UInt(4.W)
  val inverse = Bool()
}
class FilterByte extends Bundle {
  val data = UInt(8.W)
  val filter = UInt(3.W)
}

/** 单项同步读流水；输出提交推进反馈，同址转发不依赖RAM冲突语义。 */
class PngRowTransform(maxRowBytes: Int = 65536) extends Module {
  require(maxRowBytes >= 1 && maxRowBytes <= 65536)
  val io = IO(new Bundle {
    val config = Flipped(Decoupled(new RowConfig))
    val in = Flipped(Decoupled(new FilterByte))
    val out = Decoupled(new ByteBeat)
  })
  val active = RegInit(false.B)
  val pending = RegInit(false.B)
  val inputDone = RegInit(false.B)
  val cfg = Reg(new RowConfig)
  val inputCol = RegInit(0.U(17.W))
  val inputRow = RegInit(0.U(14.W))
  val inputFilter = Reg(UInt(3.W))
  val col = RegInit(0.U(17.W))
  val row = RegInit(0.U(14.W))
  val incoming = Reg(UInt(8.W))
  val filter = Reg(UInt(3.W))
  val leftHistory = Reg(Vec(8,UInt(8.W)))
  val upHistory = Reg(Vec(8,UInt(8.W)))
  val line = SyncReadMem(maxRowBytes,UInt(8.W))
  val indexBits = math.max(1,log2Ceil(maxRowBytes))
  val old = line.read(inputCol(indexBits-1,0),io.in.fire)
  val fresh = RegNext(io.in.fire,false.B)
  val forwarded = RegInit(false.B)
  val forwardedData = Reg(UInt(8.W))
  val heldAbove = Reg(UInt(8.W))
  val readAbove = Mux(row===0.U,0.U(8.W),Mux(forwarded,forwardedData,old))
  val above = Mux(fresh,readAbove,heldAbove)
  when(pending && fresh) { heldAbove := readAbove }
  io.config.ready := !active
  io.in.ready := active && !inputDone && (!pending || io.out.ready)
  io.out.valid := pending
  val hIndex = (cfg.bytesPerPixel - 1.U)(2,0)
  val hasLeft = col >= cfg.bytesPerPixel
  val a = Mux(hasLeft,leftHistory(hIndex),0.U(8.W))
  val b = above
  val c = Mux(hasLeft,upHistory(hIndex),0.U(8.W))
  // Paeth中间量在11位带符号域内精确计算；平局顺序a,b,c。
  val pa = (b.zext - c.zext).abs.asUInt
  val pb = (a.zext - c.zext).abs.asUInt
  val pc = (a.zext.pad(11) + b.zext.pad(11) - (c.zext.pad(11) << 1)).abs.asUInt
  val paeth = Mux(pa <= pb && pa <= pc,a,Mux(pb <= pc,b,c))
  val average = ((a +& b) >> 1)(7,0)
  val predictor = MuxLookup(filter,0.U(8.W))(Seq(
    0.U -> 0.U(8.W),1.U -> a,2.U -> b,3.U -> average,4.U -> paeth))
  val reconstructed = (incoming + predictor)(7,0)
  val original = Mux(cfg.inverse,reconstructed,incoming)
  io.out.bits.data := Mux(cfg.inverse,reconstructed,(incoming - predictor)(7,0))
  io.out.bits.last := col === cfg.rowBytes - 1.U && row === cfg.rows - 1.U

  when(io.config.fire) {
    assert(io.config.bits.rowBytes >= 1.U && io.config.bits.rowBytes <= maxRowBytes.U,"PNG_ROW_BYTES")
    assert(io.config.bits.rows >= 1.U && io.config.bits.rows <= 8192.U,"PNG_ROW_COUNT")
    assert(io.config.bits.bytesPerPixel >= 1.U && io.config.bits.bytesPerPixel <= 8.U,"PNG_BPP")
    cfg := io.config.bits
    inputCol := 0.U; inputRow := 0.U
    col := 0.U; row := 0.U; active := true.B; pending := false.B; inputDone := false.B
  }
  when(io.out.fire) { pending := false.B }
  when(io.in.fire) {
    incoming := io.in.bits.data
    col := inputCol; row := inputRow
    filter := Mux(inputCol===0.U,io.in.bits.filter,inputFilter)
    when(inputCol === 0.U) {
      assert(io.in.bits.filter <= 4.U,"PNG_FILTER_TYPE")
      inputFilter := io.in.bits.filter
    }
    // New read and old output write can address the same cell for a one-byte row.
    forwarded := io.out.fire && col(indexBits-1,0)===inputCol(indexBits-1,0)
    forwardedData := original
    pending := true.B
    when(inputCol===cfg.rowBytes-1.U) {
      inputCol := 0.U; inputRow := inputRow+1.U
      when(inputRow===cfg.rows-1.U) { inputDone := true.B }
    }.otherwise { inputCol := inputCol+1.U }
  }
  when(io.out.fire) {
    line.write(col(indexBits-1,0),original)
    leftHistory(0) := original; upHistory(0) := above
    for(i <- 1 until 8) {
      leftHistory(i) := leftHistory(i-1); upHistory(i) := upHistory(i-1)
    }
    when(io.out.bits.last) { active := false.B }
  }
}
