// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: FastCanonicalHuffman.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._

/** Short-code asynchronous root, registered long-code fallback. Peek is not
  * a Decoupled request: only accept consumes the returned number of bits. */
class FastCanonicalHuffman(capacity:Int=288,rootBits:Int=9,parallelLong:Boolean=false) extends Module {
  require(capacity>=1 && capacity<=288 && rootBits>=1 && rootBits<=9)
  val io=IO(new Bundle {
    val config=Flipped(Decoupled(new HuffmanConfig))
    val length=Flipped(Decoupled(UInt(4.W)))
    val query=Input(Bool());val bits=Input(UInt(15.W));val available=Input(UInt(5.W))
    val accept=Input(Bool());val valid=Output(Bool());val symbol=Output(UInt(9.W));val used=Output(UInt(4.W))
    val needed=Output(UInt(5.W));val built=Output(Bool());val failed=Output(Bool());val empty=Output(Bool())
  })
  val idle :: load :: prefix :: scan :: fillRoot :: ready :: slow :: fetch :: emit :: bad :: Nil=Enum(10)
  val state=RegInit(idle);val cfg=Reg(new HuffmanConfig)
  val counts=Reg(Vec(16,UInt(9.W)));val first=Reg(Vec(16,UInt(17.W)))
  val base=Reg(Vec(16,UInt(9.W)));val offset=Reg(Vec(16,UInt(9.W)))
  val next=Reg(Vec(16,UInt(17.W)))
  val lengths=Mem(capacity,UInt(4.W))
  val symbols=if(parallelLong)None else Some(SyncReadMem(capacity,UInt(9.W)))
  val directSymbols=if(parallelLong)Some(Mem(capacity,UInt(9.W))) else None
  val addressBits=math.max(1,log2Ceil(capacity));val rootSize=1<<rootBits
  // Pair complementary high addresses while filling a short code. Each bank
  // has only one write port; lookup capacity and low-bit prefix semantics stay.
  val rootLow=Mem(rootSize/2,UInt(13.W));val rootHigh=Mem(rootSize/2,UInt(13.W))
  val rootValid=RegInit(0.U(rootSize.W));val bankBits=math.max(1,rootBits-1)
  val index=RegInit(0.U(9.W));val maximum=RegInit(0.U(4.W));val hasEob=RegInit(false.B)
  val level=RegInit(1.U(4.W));val total=RegInit(0.U(9.W));val nextCode=RegInit(0.U(17.W))
  val slots=RegInit(1.U(17.W));val fillPrefix=Reg(UInt(rootBits.W))
  val fillValue=Reg(UInt(13.W));val fillLength=Reg(UInt(4.W))
  val fillIndex=Reg(UInt((rootBits+1).W));val fillCount=Reg(UInt((rootBits+1).W))
  val saved=Reg(UInt(15.W));val answer=Reg(UInt(9.W));val answerLength=Reg(UInt(4.W))
  io.config.ready:=state===idle || state===ready || state===bad
  io.length.ready:=state===load
  io.built:=state===ready || state===slow || state===fetch || state===emit
  io.failed:=state===bad;io.empty:=maximum===0.U
  io.valid:=false.B;io.symbol:=0.U;io.used:=0.U;io.needed:=maximum
  val rootAddress=io.bits(rootBits-1,0)
  val bankAddress=(rootAddress & (rootSize/2-1).U).pad(bankBits)
  val entry=Mux(rootAddress(rootBits-1),rootHigh(bankAddress),rootLow(bankAddress))
  val rootHit=rootValid(rootAddress);val entryLength=entry(12,9)
  when(state===ready && io.query){
    when(maximum===0.U){state:=bad}
    .elsewhen(rootHit){
      io.needed:=entryLength
      io.valid:=io.available>=entryLength
      io.symbol:=entry(8,0);io.used:=entryLength
    }.otherwise{
      if(parallelLong){
        // Constant prefix slices; no 32768-entry expanded table or extra
        // construction replay. A validated prefix tree has at most one hit.
        val levels=(rootBits+1) to 15
        val prefixes=levels.map(n=>Reverse(io.bits)(14,15-n).pad(17))
        val relatives=levels.zip(prefixes).map{case(n,p)=>p-first(n)}
        val hits=levels.indices.map(i=>io.available>=levels(i).U &&
          prefixes(i)>=first(levels(i)) && relatives(i)<counts(levels(i)))
        val any=hits.reduce(_||_)
        val addresses=levels.indices.map(i=>base(levels(i))+relatives(i))
        val address=Mux1H(hits,addresses)(addressBits-1,0)
        when(any){
          io.valid:=true.B;io.used:=Mux1H(hits,levels.map(_.U(4.W)))
          io.symbol:=directSymbols.get(address)
        }.elsewhen(io.available>=maximum){state:=bad}
        assert(PopCount(VecInit(hits))<=1.U,"FAST_HUFFMAN_CANONICAL_UNIQUE")
      }else{
        when(io.available>=maximum){
          when(maximum<=rootBits.U){state:=bad}
          .otherwise{saved:=io.bits;level:=(rootBits+1).U;state:=slow}
        }
      }
    }
  }
  assert(!io.accept || io.valid,"FAST_HUFFMAN_ACCEPT_WITHOUT_RESULT")
  if(!parallelLong){
    when(state===emit){io.valid:=true.B;io.symbol:=answer;io.used:=answerLength
      when(io.accept){state:=ready}
    }
    val prefixValue=Reverse(saved)>>(15.U-level)
    val relative=prefixValue-first(level)
    val hit=prefixValue>=first(level) && relative<counts(level)
    val fetched=symbols.get.read((base(level)+relative)(addressBits-1,0),state===slow && hit)
    when(state===slow){
      when(hit){answerLength:=level;state:=fetch}
      .elsewhen(level>=maximum){state:=bad}
      .otherwise{level:=level+1.U}
    }
    when(state===fetch){answer:=fetched;state:=emit}
  }

  def advance():Unit={index:=index+1.U;state:=scan;when(index+1.U===cfg.symbols){state:=ready}}
  when(io.length.fire){
    lengths.write(index(addressBits-1,0),io.length.bits)
    when(io.length.bits=/=0.U){counts(io.length.bits):=counts(io.length.bits)+1.U}
    when(io.length.bits>maximum){maximum:=io.length.bits}
    when(index===256.U && io.length.bits=/=0.U){hasEob:=true.B}
    index:=index+1.U
    when(index+1.U===cfg.symbols){state:=prefix;level:=1.U;nextCode:=0.U;total:=0.U;slots:=1.U}
  }
  when(state===prefix){
    val candidate=(nextCode+counts(level-1.U))<<1
    val available=(slots.zext<<1)-counts(level).zext
    first(level):=candidate;next(level):=candidate;nextCode:=candidate
    base(level):=total;offset(level):=total;total:=total+counts(level);slots:=available.asUInt
    when(available<0.S){state:=bad}
    .elsewhen(level===15.U){
      val absent=maximum===0.U;val incomplete=available=/=0.S
      val invalid=(cfg.kind===0.U && (absent || incomplete)) || (cfg.kind===1.U && !hasEob) ||
        (!absent && incomplete && maximum=/=1.U) || (absent && cfg.kind=/=2.U)
      index:=0.U
      when(invalid){state:=bad}.elsewhen(absent){state:=ready}.otherwise{state:=scan}
    }.otherwise{level:=level+1.U}
  }
  val n=lengths(index(addressBits-1,0))
  when(state===scan){
    advance()
    when(n=/=0.U){
      if(parallelLong)directSymbols.get.write(offset(n)(addressBits-1,0),index)
      else symbols.get.write(offset(n)(addressBits-1,0),index)
      offset(n):=offset(n)+1.U
      next(n):=next(n)+1.U
      when(n<=rootBits.U){
        fillPrefix:=(Reverse(next(n)(rootBits-1,0))>>(rootBits.U-n))(rootBits-1,0)
        fillValue:=Cat(n,index);fillLength:=n;fillIndex:=0.U
        fillCount:=(1.U((rootBits+1).W)<<(rootBits.U-n))(rootBits,0)
        state:=fillRoot
      }
    }
  }
  // scan has already advanced index; use that next index after the last copy.
  val writeAddress=(fillPrefix | (fillIndex<<fillLength))(rootBits-1,0)
  when(state===fillRoot){
    val pair=fillCount>1.U
    val lowAddress=(writeAddress & (rootSize/2-1).U).pad(bankBits)
    when(pair || !writeAddress(rootBits-1)){rootLow.write(lowAddress,fillValue)}
    when(pair || writeAddress(rootBits-1)){rootHigh.write(lowAddress,fillValue)}
    val firstSet=rootValid.bitSet(writeAddress,true.B)
    rootValid:=Mux(pair,firstSet.bitSet(writeAddress | (rootSize/2).U,true.B),firstSet)
    fillIndex:=fillIndex+1.U
    val iterations=Mux(pair,fillCount>>1,fillCount)
    when(fillIndex+1.U===iterations){state:=Mux(index===cfg.symbols,ready,scan)}
  }
  when(io.config.fire){
    cfg:=io.config.bits;index:=0.U;maximum:=0.U;hasEob:=false.B;rootValid:=0.U;state:=load
    for(i<-0 until 16){counts(i):=0.U;first(i):=0.U;base(i):=0.U;offset(i):=0.U;next(i):=0.U}
    when(io.config.bits.symbols===0.U || io.config.bits.symbols>capacity.U || io.config.bits.kind>2.U){state:=bad}
  }
}
