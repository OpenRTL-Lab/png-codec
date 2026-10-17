// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: Protocol.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._

class StreamBeat extends Bundle {
  val data = UInt(64.W)
  val keep = UInt(8.W)
  val last = Bool()
}
class ByteBeat extends Bundle {
  val data = UInt(8.W)
  val last = Bool()
}
class PngCommand extends Bundle {
  val tag = UInt(32.W)
  val inputBytes = UInt(32.W)
  val outputLimit = UInt(32.W)
  val imageWidth = UInt(14.W)
  val imageHeight = UInt(14.W)
  val colorType = UInt(3.W)
  val bitDepth = UInt(5.W)
  val filter = UInt(3.W)
  val fixed = Bool()
  val paletteBytes = UInt(10.W)
  val transparencyBytes = UInt(9.W)
}
class PngResult extends Bundle {
  val tag = UInt(32.W)
  val status = UInt(3.W)
  val inputBytes = UInt(32.W)
  val outputBytes = UInt(32.W)
  val pixels = UInt(32.W)
}

/** 字节组拍器只在成功输入last时标尾，背压时整拍稳定。 */
class BytePacker extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ByteBeat))
    val out = Decoupled(new StreamBeat)
    val clear = Input(Bool())
    val empty = Output(Bool())
  })
  val data = RegInit(0.U(64.W))
  val count = RegInit(0.U(4.W))
  val pending = RegInit(false.B)
  val last = RegInit(false.B)
  io.in.ready := !pending || io.out.ready
  io.out.valid := pending
  io.out.bits.data := data
  io.out.bits.keep := ((1.U(9.W) << count) - 1.U)(7,0)
  io.out.bits.last := last
  io.empty := count === 0.U && !pending
  when(io.out.fire) { pending := false.B; data := 0.U; count := 0.U; last := false.B }
  when(io.in.fire) {
    val offset = Mux(pending,0.U(4.W),count)
    val prefix = Mux(pending,0.U(64.W),data)
    data := prefix | (io.in.bits.data.pad(64) << (offset << 3))(63,0)
    count := offset + 1.U
    when(offset === 7.U || io.in.bits.last) { pending := true.B; last := io.in.bits.last }
  }
  when(io.clear) { pending := false.B; data := 0.U; count := 0.U; last := false.B }
}

object Checksums {
  def crc32(crc: UInt, data: UInt): UInt = {
    var c = crc ^ data.pad(32)
    for (_ <- 0 until 8) c = (c >> 1) ^ Mux(c(0), "hedb88320".U(32.W), 0.U(32.W))
    c
  }
  def adlerNext(s1: UInt, s2: UInt, data: UInt): (UInt, UInt) = {
    val a = s1 +& data
    val aa = Mux(a >= 65521.U, a - 65521.U, a)(15,0)
    val b = s2 +& aa
    val bb = Mux(b >= 65521.U, b - 65521.U, b)(15,0)
    (aa, bb)
  }
}
