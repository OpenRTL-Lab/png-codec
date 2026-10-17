// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: CanonicalHuffman.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._

class HuffmanConfig extends Bundle {
  val symbols=UInt(9.W)
  val kind=UInt(2.W) // 0码长树(完整)，1 literal/length(要求EOB)，2 distance(允许空)
}

/** 规范码范围+同步符号存储；不展开软件多级解码查找表。 */
class CanonicalHuffman(capacity:Int=288) extends Module {
  require(capacity>=1 && capacity<=288)
  val io=IO(new Bundle {
    val config=Flipped(Decoupled(new HuffmanConfig))
    val length=Flipped(Decoupled(UInt(4.W)))
    val bit=Flipped(Decoupled(Bool()));val symbol=Decoupled(UInt(9.W))
    val built=Output(Bool());val failed=Output(Bool());val empty=Output(Bool())
  })
  val idle :: load :: prefix :: readLength :: place :: decode :: fetch :: emit :: bad :: Nil=Enum(9)
  val state=RegInit(idle);val cfg=Reg(new HuffmanConfig)
  val counts=Reg(Vec(16,UInt(9.W)));val first=Reg(Vec(16,UInt(17.W)))
  val base=Reg(Vec(16,UInt(9.W)));val offset=Reg(Vec(16,UInt(9.W)))
  val lengths=SyncReadMem(capacity,UInt(4.W));val symbols=SyncReadMem(capacity,UInt(9.W))
  val index=RegInit(0.U(9.W));val maximum=RegInit(0.U(4.W));val hasEob=RegInit(false.B)
  val level=RegInit(1.U(4.W));val nextCode=RegInit(0.U(17.W));val total=RegInit(0.U(9.W))
  val slots=RegInit(1.U(17.W));val code=RegInit(0.U(17.W));val depth=RegInit(0.U(4.W))
  val answer=Reg(UInt(9.W));val addressBits=math.max(1,log2Ceil(capacity))
  io.config.ready:=state===idle || state===decode || state===bad
  io.length.ready:=state===load
  io.bit.ready:=state===decode
  io.symbol.valid:=state===emit;io.symbol.bits:=answer
  io.failed:=state===bad;io.built:=state===decode || state===fetch || state===emit
  io.empty:=maximum===0.U
  val lengthAt=lengths.read(index(addressBits-1,0),state===readLength)
  val grownCode=(code<<1)|io.bit.bits.asUInt
  val grownDepth=depth+&1.U
  val depthIndex=grownDepth(3,0)
  val relative=grownCode-first(depthIndex)
  val hit=grownCode>=first(depthIndex) && relative<counts(depthIndex)
  val symbolAddress=(base(depthIndex)+relative)(addressBits-1,0)
  val symbolAt=symbols.read(symbolAddress,io.bit.fire && hit && maximum=/=0.U)
  when(io.length.fire){
    lengths.write(index(addressBits-1,0),io.length.bits)
    when(io.length.bits=/=0.U){counts(io.length.bits):=counts(io.length.bits)+1.U}
    when(io.length.bits>maximum){maximum:=io.length.bits}
    when(index===256.U && io.length.bits=/=0.U){hasEob:=true.B}
    index:=index+1.U
    when(index+1.U===cfg.symbols){state:=prefix;level:=1.U;nextCode:=0.U;total:=0.U;slots:=1.U}
  }
  when(state===prefix){
    val candidateCode=(nextCode+counts(level-1.U))<<1
    val available=(slots.zext<<1)-counts(level).zext
    first(level):=candidateCode;nextCode:=candidateCode
    base(level):=total;offset(level):=total;total:=total+counts(level)
    slots:=available.asUInt
    when(available<0.S){state:=bad}
    .elsewhen(level===15.U){
      val absent=maximum===0.U
      val incomplete=available=/=0.S
      val invalid=(cfg.kind===0.U && (absent || incomplete)) ||
        (cfg.kind===1.U && !hasEob) ||
        (!absent && incomplete && maximum=/=1.U) ||
        (absent && cfg.kind=/=2.U)
      index:=0.U;code:=0.U;depth:=0.U
      when(invalid){state:=bad}
      .elsewhen(absent){state:=decode}
      .otherwise{state:=readLength}
    }.otherwise{level:=level+1.U}
  }
  when(state===readLength){state:=place}
  when(state===place){
    when(lengthAt=/=0.U){
      symbols.write(offset(lengthAt)(addressBits-1,0),index)
      offset(lengthAt):=offset(lengthAt)+1.U
    }
    index:=index+1.U;state:=readLength
    when(index+1.U===cfg.symbols){state:=decode}
  }
  when(io.bit.fire){
    code:=grownCode;depth:=grownDepth
    when(maximum===0.U){state:=bad}
    .elsewhen(hit){state:=fetch}
    .elsewhen(grownDepth>=maximum){state:=bad}
  }
  when(state===fetch){answer:=symbolAt;state:=emit}
  when(io.symbol.fire){code:=0.U;depth:=0.U;state:=decode}
  when(io.config.fire){
    cfg:=io.config.bits;index:=0.U;maximum:=0.U;hasEob:=false.B
    code:=0.U;depth:=0.U;state:=load
    for(i<-0 until 16){counts(i):=0.U;first(i):=0.U;base(i):=0.U;offset(i):=0.U}
    when(io.config.bits.symbols===0.U || io.config.bits.symbols>capacity.U || io.config.bits.kind>2.U){state:=bad}
  }
}
