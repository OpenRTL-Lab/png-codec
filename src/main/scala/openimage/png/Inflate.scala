// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: Inflate.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._

class InflateConfig extends Bundle {val outputBytes=UInt(32.W)}

/** RFC1950/1951串行位池、规范码树和同步32KiB历史；不依赖PNG块边界。 */
class Inflate extends Module {
  val io=IO(new Bundle {
    val config=Flipped(Decoupled(new InflateConfig))
    val in=Flipped(Decoupled(UInt(8.W)));val inputEnded=Input(Bool())
    val out=Decoupled(new ByteBeat);val done=Decoupled(UInt(3.W))
    val outputBytes=Output(UInt(32.W));val inputBytes=Output(UInt(32.W))
  })
  private val phases=Enum(30)
  val idle=phases(0);val cmf=phases(1);val flg=phases(2);val block=phases(3)
  val alignStored=phases(4);val storedLength=phases(5);val storedNeg=phases(6);val storedByte=phases(7)
  val dynHeader=phases(8);val dynCodeRead=phases(9);val dynCodeConfig=phases(10);val dynCodeLoad=phases(11)
  val dynCodeWait=phases(12);val dynLengths=phases(13);val dynRepeat=phases(14);val repeatFill=phases(15)
  val mainConfig=phases(16);val litLoad=phases(17);val distLoad=phases(18);val treesWait=phases(19)
  val litDecode=phases(20);val lenExtra=phases(21);val distDecode=phases(22);val distExtra=phases(23)
  val copyRead=phases(24);val copyWait=phases(25);val emitByte=phases(26);val alignAdler=phases(27)
  val adlerBytes=phases(28);val finish=phases(29)
  val state=RegInit(idle);val afterEmit=RegInit(idle)
  val expected=Reg(UInt(32.W));val produced=RegInit(0.U(32.W));val consumed=RegInit(0.U(32.W))
  val status=RegInit(0.U(3.W));val pool=RegInit(0.U(32.W));val available=RegInit(0.U(6.W))
  val cmfValue=Reg(UInt(8.W));val window=Reg(UInt(16.W));val finalBlock=Reg(Bool())
  val remaining=Reg(UInt(16.W));val stored=Reg(UInt(16.W))
  val output=Reg(UInt(8.W));val writePtr=RegInit(0.U(15.W))
  val history=SyncReadMem(32768,UInt(8.W))
  val distance=Reg(UInt(16.W));val copyLeft=Reg(UInt(9.W))
  val adler1=RegInit(1.U(16.W));val adler2=RegInit(0.U(16.W))
  val adlerValue=RegInit(0.U(32.W));val adlerIndex=RegInit(0.U(2.W))
  val fixed=Reg(Bool());val nlit=Reg(UInt(9.W));val ndist=Reg(UInt(6.W));val ncode=Reg(UInt(5.W))
  val lengths=Reg(Vec(320,UInt(4.W)));val codeLengths=Reg(Vec(19,UInt(4.W)))
  val cursor=RegInit(0.U(9.W));val previous=RegInit(0.U(4.W));val repeatValue=Reg(UInt(4.W))
  val repeatBase=Reg(UInt(8.W));val repeatExtra=Reg(UInt(3.W))
  val repeatLeft=Reg(UInt(8.W))
  val extra=Reg(UInt(4.W));val lengthBase=Reg(UInt(9.W));val distanceBase=Reg(UInt(16.W))
  io.config.ready:=state===idle;io.out.valid:=state===emitByte
  io.out.bits.data:=output;io.out.bits.last:=produced+1.U===expected
  io.done.valid:=state===finish;io.done.bits:=status
  io.outputBytes:=produced;io.inputBytes:=consumed
  val treeReset=reset.asBool || io.config.fire
  val lit=withReset(treeReset){Module(new CanonicalHuffman(288))}
  val dist=withReset(treeReset){Module(new CanonicalHuffman(32))}
  val cl=withReset(treeReset){Module(new CanonicalHuffman(19))}
  lit.io.config.valid:=false.B;lit.io.config.bits.symbols:=nlit;lit.io.config.bits.kind:=1.U
  dist.io.config.valid:=false.B;dist.io.config.bits.symbols:=ndist;dist.io.config.bits.kind:=2.U
  cl.io.config.valid:=state===dynCodeConfig;cl.io.config.bits.symbols:=19.U;cl.io.config.bits.kind:=0.U
  lit.io.length.valid:=state===litLoad;lit.io.length.bits:=0.U
  dist.io.length.valid:=state===distLoad;dist.io.length.bits:=0.U
  cl.io.length.valid:=state===dynCodeLoad;cl.io.length.bits:=codeLengths(cursor(4,0))
  for(t<-Seq(lit,dist,cl)){
    t.io.bit.valid:=false.B;t.io.bit.bits:=pool(0);t.io.symbol.ready:=false.B
  }
  io.in.ready:=false.B
  val refill=WireDefault(false.B);val take=WireDefault(0.U(5.W))
  def fail(n:UInt=3.U):Unit={status:=n;state:=finish}
  def field(n:UInt)(body:UInt=>Unit):Unit={
    when(available>=n){
      take:=n
      val mask=((1.U(33.W)<<n)-1.U)(31,0)
      body(pool & mask)
    }.otherwise{refill:=true.B}
  }
  def field(n:Int)(body:UInt=>Unit):Unit=field(n.U(5.W))(body)
  def decode(tree:CanonicalHuffman)(body:UInt=>Unit):Unit={
    when(tree.io.failed){fail()}
    .otherwise{
      tree.io.symbol.ready:=true.B
      when(tree.io.symbol.fire){body(tree.io.symbol.bits)}
      .elsewhen(tree.io.bit.ready){
        when(available=/=0.U){tree.io.bit.valid:=true.B;when(tree.io.bit.fire){take:=1.U}}
        .otherwise{refill:=true.B}
      }
    }
  }
  def endBlock():Unit={
    when(finalBlock){
      when(produced=/=expected){fail()}.otherwise{state:=alignAdler}
    }.otherwise{state:=block}
  }
  def emit(value:UInt,next:UInt):Unit={
    when(produced>=expected){fail()}
    .otherwise{output:=value;afterEmit:=next;state:=emitByte}
  }
  val order=VecInit(Seq(16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15).map(_.U(5.W)))
  val lenBases=VecInit(Seq(3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258).map(_.U(9.W)))
  val lenExtras=VecInit((Seq.fill(8)(0)++(1 to 5).flatMap(n=>Seq.fill(4)(n))++Seq(0)).map(_.U(4.W)))
  val distBases=VecInit(Seq(1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577).map(_.U(16.W)))
  val distExtras=VecInit((Seq.fill(4)(0)++(1 to 13).flatMap(n=>Seq(n,n))).map(_.U(4.W)))
  val copied=history.read((writePtr-distance)(14,0),state===copyRead)
  switch(state){
    is(cmf){field(8){v=>cmfValue:=v;state:=flg}}
    is(flg){field(8){v=>
      window:=(1.U(16.W)<<(cmfValue(7,4)+&8.U))(15,0)
      when(cmfValue(3,0)=/=8.U || cmfValue(7,4)>7.U || Cat(cmfValue,v(7,0))%31.U=/=0.U){fail()}
      .elsewhen(v(5)){fail(2.U)}.otherwise{state:=block}
    }}
    is(block){field(3){v=>
      finalBlock:=v(0)
      switch(v(2,1)){
        is(0.U){state:=alignStored}
        is(1.U){fixed:=true.B;nlit:=288.U;ndist:=32.U;state:=mainConfig}
        is(2.U){fixed:=false.B;state:=dynHeader}
        is(3.U){fail()}
      }
    }}
    is(alignStored){take:=available(2,0);state:=storedLength}
    is(storedLength){field(16){v=>stored:=v;remaining:=v;state:=storedNeg}}
    is(storedNeg){field(16){v=>
      when((v(15,0)^stored)=/="hffff".U){fail()}
      .elsewhen(stored===0.U){endBlock()}.otherwise{state:=storedByte}
    }}
    is(storedByte){field(8){v=>emit(v,storedByte);remaining:=remaining-1.U}}
    is(dynHeader){field(14){v=>
      nlit:=v(4,0)+&257.U;ndist:=v(9,5)+&1.U;ncode:=v(13,10)+&4.U
      for(i<-0 until 19){codeLengths(i):=0.U}
      cursor:=0.U;state:=dynCodeRead
      when(v(4,0)>29.U){fail()}
    }}
    is(dynCodeRead){field(3){v=>
      codeLengths(order(cursor(4,0))):=v;cursor:=cursor+1.U
      when(cursor+1.U===ncode){state:=dynCodeConfig}
    }}
    is(dynCodeConfig){when(cl.io.config.fire){cursor:=0.U;state:=dynCodeLoad}}
    is(dynCodeLoad){when(cl.io.length.fire){cursor:=cursor+1.U;when(cursor===18.U){state:=dynCodeWait}}}
    is(dynCodeWait){
      when(cl.io.failed){fail()}.elsewhen(cl.io.built){cursor:=0.U;previous:=0.U;state:=dynLengths}
    }
    is(dynLengths){decode(cl){s=>
      when(s<16.U){
        lengths(cursor):=s;previous:=s;cursor:=cursor+1.U
        when(cursor+1.U===nlit+&ndist){state:=mainConfig}
      }.otherwise{
        repeatValue:=Mux(s===16.U,previous,0.U)
        repeatBase:=Mux(s===18.U,11.U,3.U);repeatExtra:=Mux(s===18.U,7.U,Mux(s===17.U,3.U,2.U))
        state:=dynRepeat
        when(s>18.U || (s===16.U && cursor===0.U)){fail()}
      }
    }}
    is(dynRepeat){field(repeatExtra){v=>
      val count=v+&repeatBase
      when(cursor+&count>nlit+&ndist){fail()}
      .otherwise{
        repeatLeft:=count;previous:=repeatValue;state:=repeatFill
      }
    }}
    is(repeatFill){
      lengths(cursor):=repeatValue;cursor:=cursor+1.U;repeatLeft:=repeatLeft-1.U
      when(repeatLeft===1.U){state:=dynLengths;when(cursor+1.U===nlit+&ndist){state:=mainConfig}}
    }
    is(mainConfig){
      // 两树仅在均可接收时同时握手，避免其中一棵提前开始第二次配置。
      lit.io.config.valid:=dist.io.config.ready;dist.io.config.valid:=lit.io.config.ready
      when(lit.io.config.fire && dist.io.config.fire){cursor:=0.U;state:=litLoad}
    }
    is(litLoad){
      lit.io.length.bits:=Mux(fixed,Mux(cursor<144.U,8.U,Mux(cursor<256.U,9.U,Mux(cursor<280.U,7.U,8.U))),lengths(cursor))
      when(lit.io.length.fire){cursor:=cursor+1.U;when(cursor+1.U===nlit){cursor:=0.U;state:=distLoad}}
    }
    is(distLoad){
      dist.io.length.bits:=Mux(fixed,5.U,lengths((nlit+cursor)(8,0)))
      when(dist.io.length.fire){cursor:=cursor+1.U;when(cursor+1.U===ndist){state:=treesWait}}
    }
    is(treesWait){when(lit.io.failed || dist.io.failed){fail()}.elsewhen(lit.io.built && dist.io.built){state:=litDecode}}
    is(litDecode){decode(lit){s=>
      when(s<256.U){emit(s,litDecode)}
      .elsewhen(s===256.U){endBlock()}
      .elsewhen(s<=285.U){val i=(s-257.U)(4,0);lengthBase:=lenBases(i);extra:=lenExtras(i);state:=lenExtra}
      .otherwise{fail()}
    }}
    is(lenExtra){field(extra){v=>copyLeft:=lengthBase+v;state:=distDecode}}
    is(distDecode){decode(dist){s=>
      when(s>=30.U){fail()}.otherwise{distanceBase:=distBases(s(4,0));extra:=distExtras(s(4,0));state:=distExtra}
    }}
    is(distExtra){field(extra){v=>
      val d=distanceBase+&v
      when(d===0.U || d>32768.U || d>window || d>produced){fail()}
      .otherwise{distance:=d;state:=copyRead}
    }}
    is(copyRead){state:=copyWait}
    is(copyWait){emit(copied,copyRead)}
    is(emitByte){when(io.out.fire){
      history.write(writePtr,output);writePtr:=writePtr+1.U;produced:=produced+1.U
      val (a,b)=Checksums.adlerNext(adler1,adler2,output);adler1:=a;adler2:=b
      state:=afterEmit
      when(afterEmit===copyRead){copyLeft:=copyLeft-1.U;when(copyLeft===1.U){state:=litDecode}}
      when(afterEmit===storedByte && remaining===0.U){
        when(finalBlock){when(produced+1.U=/=expected){fail()}.otherwise{state:=alignAdler}}
        .otherwise{state:=block}
      }
    }}
    is(alignAdler){take:=available(2,0);adlerValue:=0.U;adlerIndex:=0.U;state:=adlerBytes}
    is(adlerBytes){field(8){v=>
      val next=Cat(adlerValue(23,0),v(7,0));adlerValue:=next;adlerIndex:=adlerIndex+1.U
      when(adlerIndex===3.U){state:=finish;when(next=/=Cat(adler2,adler1)){status:=4.U}}
    }}
    is(finish){when(io.done.fire){state:=idle}}
  }
  when(take=/=0.U){pool:=pool>>take;available:=available-take}
  when(refill){
    io.in.ready:=true.B
    when(io.in.fire){pool:=pool|(io.in.bits.pad(32)<<available(4,0))(31,0);available:=available+8.U;consumed:=consumed+1.U}
    .elsewhen(io.inputEnded && !io.in.valid){fail()}
  }
  when(io.config.fire){
    expected:=io.config.bits.outputBytes;produced:=0.U;consumed:=0.U;status:=0.U
    pool:=0.U;available:=0.U;writePtr:=0.U;adler1:=1.U;adler2:=0.U;state:=cmf
  }
}
