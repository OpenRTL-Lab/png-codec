// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: WideZlibEncoder.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._

/** 原创distance1 RLE；每周期接收1原始字节，位池同拍入/出最多2压缩字节。 */
class WideZlibEncoder extends Module {
  val io=IO(new Bundle {
    val config=Flipped(Decoupled(new ZlibConfig))
    val in=Flipped(Decoupled(new ByteBeat));val out=Decoupled(new WideBytes)
  })
  val idle :: zHead :: storedHead :: storedData :: fixedHead :: data :: eob :: align :: adler :: flush :: Nil=Enum(10)
  val state=RegInit(idle);val fixed=Reg(Bool())
  val remaining=Reg(UInt(32.W));val blockLeft=Reg(UInt(16.W))
  val pool=RegInit(0.U(64.W));val bitCount=RegInit(0.U(7.W))
  val s1=RegInit(1.U(16.W));val s2=RegInit(0.U(16.W))
  val havePrevious=RegInit(false.B);val previous=Reg(UInt(8.W))
  val copies=RegInit(0.U(9.W))
  // A bounded early prefix makes the first image-bearing IDAT visible without
  // waiting for a 258-byte run. Later matches retain the full length range.
  val earlyInputs=RegInit(0.U(7.W))
  io.config.ready:=state===idle
  // Non-final output always carries two bytes, simplifying bank ownership.
  io.out.valid:=bitCount>=16.U || (state===flush && bitCount>=8.U)
  io.out.bits.data:=pool(15,0)
  io.out.bits.bytes:=Mux(bitCount>=16.U,2.U,1.U)
  io.out.bits.last:=state===flush && bitCount<=16.U
  val removed=Mux(io.out.fire,Mux(bitCount>=16.U,16.U(7.W),8.U(7.W)),0.U(7.W))
  val baseCount=bitCount-removed
  val basePool=pool>>removed
  val append=WireDefault(false.B);val appendBits=WireDefault(0.U(64.W));val appendCount=WireDefault(0.U(7.W))
  def put(bits: UInt,count: UInt): Unit={append:=true.B;appendBits:=bits;appendCount:=count}
  def code(symbol: UInt): (UInt,UInt)={
    val bits=WireDefault(0.U(64.W));val count=WireDefault(0.U(7.W))
    when(symbol<144.U) { bits:=Reverse((symbol+48.U)(7,0));count:=8.U }
      .elsewhen(symbol<256.U) { bits:=Reverse((symbol-144.U+400.U)(8,0));count:=9.U }
      .elsewhen(symbol<280.U) { bits:=Reverse((symbol-256.U)(6,0));count:=7.U }
      .otherwise { bits:=Reverse((symbol-280.U+192.U)(7,0));count:=8.U }
    (bits,count)
  }
  val bases=VecInit(Seq(3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,
    35,43,51,59,67,83,99,115,131,163,195,227,258).map(_.U(9.W)))
  val extras=VecInit((Seq.fill(8)(0)++Seq.fill(4)(1)++Seq.fill(4)(2)++Seq.fill(4)(3)++
    Seq.fill(4)(4)++Seq.fill(4)(5)++Seq(0)).map(_.U(3.W)))
  val same=havePrevious && io.in.bits.data===previous
  val toEncode=Mux(same,copies+1.U,copies)
  val lengthIndex=WireDefault(0.U(5.W))
  for(i<-0 until 29) { when(toEncode>=bases(i)) { lengthIndex:=i.U } }
  val (lengthCode,lengthCodeCount)=code(lengthIndex.pad(9)+257.U)
  val lengthToken=lengthCode | ((toEncode-bases(lengthIndex)).pad(64)<<lengthCodeCount)(63,0)
  val lengthCount=lengthCodeCount+extras(lengthIndex)+5.U
  val (previousCode,previousCount)=code(previous.pad(9))
  val shortToken=previousCode | Mux(toEncode===2.U,(previousCode<<previousCount)(63,0),0.U(64.W))
  val repeatBits=Mux(toEncode===0.U,0.U(64.W),Mux(toEncode>=3.U,lengthToken,shortToken))
  val repeatCount=Mux(toEncode===0.U,0.U(7.W),Mux(toEncode>=3.U,lengthCount,
    Mux(toEncode===2.U,previousCount<<1,previousCount)))
  val (literalCode,literalCount)=code(io.in.bits.data.pad(9))
  io.in.ready:=(state===storedData && baseCount<=56.U) || (state===data && baseCount<=37.U)
  when(io.out.fire || append) {
    pool:=basePool | (appendBits<<baseCount)(63,0)
    bitCount:=baseCount+appendCount
  }
  when(append) { assert(baseCount+&appendCount<=64.U,"WIDE_ZLIB_POOL") }
  when(io.config.fire) {
    assert(io.config.bits.inputBytes=/=0.U,"WIDE_ZLIB_NONEMPTY")
    fixed:=io.config.bits.fixed;remaining:=io.config.bits.inputBytes;state:=zHead
    pool:=0.U;bitCount:=0.U;s1:=1.U;s2:=0.U;havePrevious:=false.B;copies:=0.U;earlyInputs:=0.U
  }
  when(io.in.fire) {
    assert(remaining=/=0.U && io.in.bits.last===(remaining===1.U),"WIDE_ZLIB_INPUT_LENGTH")
    remaining:=remaining-1.U
    when(earlyInputs<96.U) { earlyInputs:=earlyInputs+1.U }
    val (a,b)=Checksums.adlerNext(s1,s2,io.in.bits.data);s1:=a;s2:=b
  }
  when(state===zHead && baseCount<=48.U) {
    put("h0178".U,16.U);state:=Mux(fixed,fixedHead,storedHead)
  }
  when(state===storedHead && baseCount<=24.U) {
    val n=Mux(remaining>32768.U,32768.U,remaining)(15,0)
    put(Cat(~n,n,Mux(remaining<=32768.U,1.U(8.W),0.U(8.W))),40.U)
    blockLeft:=n;state:=storedData
  }
  when(state===storedData && io.in.fire) {
    put(io.in.bits.data,8.U);blockLeft:=blockLeft-1.U
    when(blockLeft===1.U) { state:=Mux(io.in.bits.last,adler,storedHead) }
  }
  when(state===fixedHead && baseCount<=61.U) { put(3.U,3.U);state:=data }
  when(state===data && io.in.fire) {
    when(same) {
      when(toEncode===258.U || (earlyInputs<96.U && toEncode===32.U) || io.in.bits.last) {
        put(repeatBits,repeatCount);copies:=0.U
      }
        .otherwise { copies:=toEncode }
    }.otherwise {
      put(repeatBits | (literalCode<<repeatCount)(63,0),repeatCount+literalCount)
      previous:=io.in.bits.data;havePrevious:=true.B;copies:=0.U
    }
    when(io.in.bits.last) { state:=eob }
  }
  when(state===eob && baseCount<=57.U) { put(0.U,7.U);state:=align }
  when(state===align) {
    val padding=(0.U(3.W)-baseCount(2,0))(2,0)
    when(baseCount<=56.U) {
      when(padding=/=0.U) { put(0.U,padding) }
      state:=adler
    }
  }
  when(state===adler && baseCount<=32.U) {
    put(Cat(s1(7,0),s1(15,8),s2(7,0),s2(15,8)),32.U);state:=flush
  }
  when(state===flush && io.out.fire && io.out.bits.last) { state:=idle }
}
