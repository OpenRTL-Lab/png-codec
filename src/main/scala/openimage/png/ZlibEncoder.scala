// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: ZlibEncoder.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._

class ZlibConfig extends Bundle {
  val inputBytes = UInt(32.W)
  val fixed = Bool()
}

/** 完整zlib流；FIXED原创单候选distance=1匹配，解码不依赖此限制。 */
class ZlibEncoder extends Module {
  val io = IO(new Bundle {
    val config = Flipped(Decoupled(new ZlibConfig))
    val in = Flipped(Decoupled(new ByteBeat))
    val out = Decoupled(new ByteBeat)
  })
  val idle :: zHead :: storedHead :: storedData :: fixedHead :: needInput :: collect :: literal :: run :: eob :: align :: finalByte :: adler :: Nil = Enum(13)
  val state = RegInit(idle)
  val cfg = Reg(new ZlibConfig)
  val remaining = Reg(UInt(32.W))
  val sub = RegInit(0.U(3.W))
  val blockLength = Reg(UInt(16.W))
  val blockLeft = Reg(UInt(16.W))
  val s1 = RegInit(1.U(16.W));val s2 = RegInit(0.U(16.W))
  val pool = RegInit(0.U(32.W))
  val bitCount = RegInit(0.U(6.W))
  val havePrevious = RegInit(false.B)
  val previous = Reg(UInt(8.W))
  val literalByte = Reg(UInt(8.W))
  val literalFinal = Reg(Bool())
  val pendingLiteral = RegInit(false.B)
  val runLength = RegInit(0.U(9.W))
  val runFinal = RegInit(false.B)
  val fixedStates = state === fixedHead || state === needInput || state === collect ||
    state === literal || state === run || state === eob || state === align || state === finalByte
  val canAppend = bitCount < 8.U
  val append = WireDefault(false.B)
  val appendBits = WireDefault(0.U(32.W))
  val appendCount = WireDefault(0.U(6.W))
  io.config.ready := state === idle
  io.in.ready := state === needInput || state === collect || (state === storedData && io.out.ready)
  io.out.valid := fixedStates && !canAppend
  io.out.bits.data := pool(7,0)
  io.out.bits.last := false.B

  def put(bits: UInt,count: UInt): Unit = {
    append := true.B;appendBits := bits;appendCount := count
  }
  def fixedCode(symbol: UInt): (UInt,UInt) = {
    val bits=Wire(UInt(32.W));val count=Wire(UInt(6.W))
    bits := 0.U;count := 0.U
    when(symbol < 144.U) { bits := Reverse((symbol+48.U)(7,0));count := 8.U }
    .elsewhen(symbol < 256.U) { bits := Reverse((symbol-144.U+400.U)(8,0));count := 9.U }
    .elsewhen(symbol < 280.U) { bits := Reverse((symbol-256.U)(6,0));count := 7.U }
    .otherwise { bits := Reverse((symbol-280.U+192.U)(7,0));count := 8.U }
    (bits,count)
  }
  val lengthBases=VecInit(Seq(3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,
    35,43,51,59,67,83,99,115,131,163,195,227,258).map(_.U(9.W)))
  val lengthExtras=VecInit((Seq.fill(8)(0)++Seq.fill(4)(1)++Seq.fill(4)(2)++
    Seq.fill(4)(3)++Seq.fill(4)(4)++Seq.fill(4)(5)++Seq(0)).map(_.U(3.W)))
  val lengthIndex=WireDefault(0.U(5.W))
  for(i <- 0 until 29) when(runLength >= lengthBases(i)) { lengthIndex := i.U }
  val (lengthCode,lengthCodeBits)=fixedCode(lengthIndex.pad(9)+257.U)
  val lengthExtra=runLength-lengthBases(lengthIndex)
  val lengthToken=lengthCode | ((lengthExtra.pad(32) << lengthCodeBits)(31,0))
  val lengthTokenBits=lengthCodeBits+lengthExtras(lengthIndex)+5.U // distance=1码字为00000

  when(io.config.fire) {
    assert(io.config.bits.inputBytes =/= 0.U,"ZLIB_NONEMPTY_INPUT")
    cfg := io.config.bits;remaining := io.config.bits.inputBytes
    sub := 0.U;state := zHead
    pool := 0.U;bitCount := 0.U;havePrevious := false.B
    runLength := 0.U;pendingLiteral := false.B;runFinal := false.B
    s1 := 1.U;s2 := 0.U
    blockLength := Mux(io.config.bits.inputBytes > 32768.U,32768.U,io.config.bits.inputBytes)(15,0)
    blockLeft := Mux(io.config.bits.inputBytes > 32768.U,32768.U,io.config.bits.inputBytes)(15,0)
  }
  when(io.in.fire) {
    assert(remaining =/= 0.U && io.in.bits.last === (remaining === 1.U),"ZLIB_INPUT_LENGTH")
    remaining := remaining - 1.U
    val (a,b)=Checksums.adlerNext(s1,s2,io.in.bits.data);s1 := a;s2 := b
  }
  when(fixedStates && io.out.fire) { pool := pool >> 8;bitCount := bitCount - 8.U }
  when(append) {
    assert(canAppend && appendCount <= 18.U,"ZLIB_BITPOOL_BOUND")
    pool := pool | (appendBits << bitCount)(31,0)
    bitCount := bitCount + appendCount
  }
  when(state === zHead) {
    io.out.valid := true.B;io.out.bits.data := Mux(sub === 0.U,"h78".U,1.U)
    when(io.out.fire) {
      sub := 0.U
      when(sub === 0.U) { sub := 1.U }
      .otherwise { state := Mux(cfg.fixed,fixedHead,storedHead) }
    }
  }
  when(state === storedHead) {
    io.out.valid := true.B
    io.out.bits.data := MuxLookup(sub,0.U(8.W))(Seq(
      0.U -> Mux(remaining <= 32768.U,1.U(8.W),0.U(8.W)),
      1.U -> blockLength(7,0),2.U -> blockLength(15,8),
      3.U -> (~blockLength(7,0)),4.U -> (~blockLength(15,8))))
    when(io.out.fire) {
      sub := sub + 1.U
      when(sub === 4.U) { sub := 0.U;state := storedData }
    }
  }
  when(state === storedData) {
    io.out.valid := io.in.valid;io.out.bits.data := io.in.bits.data
    when(io.in.fire) {
      blockLeft := blockLeft - 1.U
      when(blockLeft === 1.U) {
        state := storedHead;sub := 0.U
        blockLength := Mux(remaining-1.U > 32768.U,32768.U,remaining-1.U)(15,0)
        blockLeft := Mux(remaining-1.U > 32768.U,32768.U,remaining-1.U)(15,0)
        when(remaining === 1.U) { state := adler }
      }
    }
  }
  when(state === fixedHead && canAppend) { put(3.U,3.U);state := needInput }
  when(state === needInput && io.in.fire) {
    when(havePrevious && io.in.bits.data === previous) {
      runLength := 1.U;runFinal := io.in.bits.last;pendingLiteral := false.B
      state := Mux(io.in.bits.last,run,collect)
    }.otherwise {
      literalByte := io.in.bits.data;literalFinal := io.in.bits.last;state := literal
    }
  }
  when(state === collect && io.in.fire) {
    when(io.in.bits.data === previous) {
      runLength := runLength + 1.U;runFinal := io.in.bits.last
      when(runLength === 257.U || io.in.bits.last) { state := run }
    }.otherwise {
      literalByte := io.in.bits.data;literalFinal := io.in.bits.last
      pendingLiteral := true.B;runFinal := false.B;state := run
    }
  }
  when(state === literal && canAppend) {
    val (code,count)=fixedCode(literalByte.pad(9));put(code,count)
    previous := literalByte;havePrevious := true.B;pendingLiteral := false.B
    state := Mux(literalFinal,eob,needInput)
  }
  when(state === run && canAppend) {
    val (code,count)=fixedCode(previous.pad(9))
    when(runLength < 3.U) {
      put(code,count);runLength := runLength - 1.U
    }.otherwise { put(lengthToken,lengthTokenBits);runLength := 0.U }
    when(runLength === 1.U || runLength >= 3.U) {
      state := Mux(pendingLiteral,literal,Mux(runFinal,eob,needInput))
    }
  }
  when(state === eob && canAppend) { put(0.U,7.U);state := align }
  when(state === align && canAppend) {
    when(bitCount === 0.U) { state := adler;sub := 0.U }
    .otherwise { put(0.U,8.U-bitCount);state := finalByte }
  }
  when(state === finalByte && io.out.fire) { state := adler;sub := 0.U }
  when(state === adler) {
    io.out.valid := true.B
    io.out.bits.data := MuxLookup(sub,0.U(8.W))(Seq(
      0.U -> s2(15,8),1.U -> s2(7,0),2.U -> s1(15,8),3.U -> s1(7,0)))
    io.out.bits.last := sub === 3.U
    when(io.out.fire) {
      sub := sub + 1.U
      when(sub === 3.U) { state := idle }
    }
  }
}
