// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: FastInflate.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._

/** RFC1950/1951: simultaneous 16-bit refill/consume, one-byte output skid,
  * short-code root and synchronous history prefetch. External PNG ABI is not
  * changed by this internal two-byte input. */
class FastInflate extends Module {
  val io=IO(new Bundle {
    val config=Flipped(Decoupled(new InflateConfig))
    val in=Flipped(Decoupled(new WideBytes));val inputEnded=Input(Bool())
    val out=Decoupled(new ByteBeat);val done=Decoupled(UInt(3.W))
    val outputBytes=Output(UInt(32.W));val inputBytes=Output(UInt(32.W))
  })
  private val phases=Enum(31)
  val idle=phases(0);val cmf=phases(1);val flg=phases(2);val block=phases(3)
  val alignStored=phases(4);val storedLength=phases(5);val storedNeg=phases(6);val storedByte=phases(7)
  val dynHeader=phases(8);val dynCodeRead=phases(9);val dynCodeConfig=phases(10);val dynCodeLoad=phases(11)
  val dynCodeWait=phases(12);val dynLengths=phases(13);val dynRepeat=phases(14);val repeatFill=phases(15)
  val mainConfig=phases(16);val litLoad=phases(17);val distLoad=phases(18);val treesWait=phases(19)
  val litDecode=phases(20);val lenExtra=phases(21);val distDecode=phases(22);val distExtra=phases(23)
  val copyPrepare=phases(24);val copyStream=phases(25);val alignAdler=phases(26)
  val waitOutput=phases(27);val adlerBytes=phases(28);val finish=phases(29);val reservedPhase=phases(30)
  val state=RegInit(idle)
  val expected=Reg(UInt(32.W));val decoded=RegInit(0.U(32.W));val produced=RegInit(0.U(32.W))
  val consumed=RegInit(0.U(32.W));val status=RegInit(0.U(3.W))
  val pool=RegInit(0.U(64.W));val available=RegInit(0.U(7.W));val take=WireDefault(0.U(5.W))
  val cmfValue=Reg(UInt(8.W));val window=Reg(UInt(16.W));val finalBlock=Reg(Bool())
  val remaining=Reg(UInt(16.W));val stored=Reg(UInt(16.W));val fixed=Reg(Bool())
  val pending=RegInit(false.B);val output=Reg(UInt(8.W));val lastCommitted=Reg(UInt(8.W))
  val outputSpace= !pending || io.out.ready
  val history=SyncReadMem(32768,UInt(8.W))
  val distance=Reg(UInt(16.W));val copyLeft=Reg(UInt(9.W))
  val copyEnd=Reg(UInt(32.W)) // invariant in copyStream: decoded + copyLeft
  // One registered future match, parsed while the current match copies.
  // Literal/EOB remains unconsumed for the ordinary token boundary.
  val aheadEmpty :: aheadLength :: aheadMatch :: aheadFault :: Nil=Enum(4)
  val ahead=RegInit(aheadEmpty)
  val nextLength=Reg(UInt(9.W));val nextDistance=Reg(UInt(16.W))
  val adler1=RegInit(1.U(16.W));val adler2=RegInit(0.U(16.W))
  val adlerValue=RegInit(0.U(32.W));val adlerIndex=RegInit(0.U(2.W))
  val nlit=Reg(UInt(9.W));val ndist=Reg(UInt(6.W));val ncode=Reg(UInt(5.W))
  val codeLengths=Reg(Vec(19,UInt(4.W)))
  val cursor=RegInit(0.U(9.W));val previous=RegInit(0.U(4.W));val repeatValue=Reg(UInt(4.W))
  val repeatBase=Reg(UInt(8.W));val repeatExtra=Reg(UInt(3.W));val repeatLeft=Reg(UInt(8.W))
  val extra=Reg(UInt(4.W));val lengthBase=Reg(UInt(9.W));val distanceBase=Reg(UInt(16.W))
  io.config.ready:=state===idle
  io.out.valid:=pending;io.out.bits.data:=output;io.out.bits.last:=produced+1.U===expected
  io.done.valid:=state===finish && !pending;io.done.bits:=status
  io.outputBytes:=produced;io.inputBytes:=consumed
  val active=state=/=idle && state=/=finish
  io.in.ready:=active && available<=48.U
  // Trees have no externally visible state during parent reset/idle. Register
  // that private cancellation fanout; every accepted config still clears them
  // immediately, before any CMF/FLG or dynamic lengths can be consumed.
  // Parent state/pending and all public reset cancellation remain synchronous
  // to the original reset edge, without an extra public reset cycle.
  val treeReset=RegNext(reset.asBool,true.B) || io.config.fire
  val lit=withReset(treeReset){Module(new FastCanonicalHuffman(288,9,parallelLong=true))}
  val dist=withReset(treeReset){Module(new FastCanonicalHuffman(32,7,parallelLong=true))}
  val cl=withReset(treeReset){Module(new FastCanonicalHuffman(19,7))}
  lit.io.config.valid:=false.B;lit.io.config.bits.symbols:=nlit;lit.io.config.bits.kind:=1.U
  dist.io.config.valid:=false.B;dist.io.config.bits.symbols:=ndist;dist.io.config.bits.kind:=2.U
  lit.io.length.valid:=false.B;lit.io.length.bits:=0.U
  dist.io.length.valid:=false.B;dist.io.length.bits:=0.U
  val lengthReady=Mux(cursor<nlit,lit.io.length.ready,dist.io.length.ready)
  def sendLength(v:UInt):Unit={
    lit.io.length.valid:=cursor<nlit;lit.io.length.bits:=v
    dist.io.length.valid:=cursor>=nlit;dist.io.length.bits:=v
  }
  for(t<-Seq(lit,dist,cl)){
    t.io.query:=false.B;t.io.bits:=pool(14,0)
    t.io.available:=Mux(available>15.U,15.U,available)(4,0);t.io.accept:=false.B
  }
  cl.io.config.valid:=false.B;cl.io.config.bits.symbols:=19.U;cl.io.config.bits.kind:=0.U
  cl.io.length.valid:=state===dynCodeLoad;cl.io.length.bits:=codeLengths(cursor(4,0))
  val fault=WireDefault(false.B);val faultCode=WireDefault(3.U(3.W))
  def fail(n:UInt=3.U):Unit={fault:=true.B;faultCode:=n}
  def requireBits(n:UInt):Unit={when(available<n && io.inputEnded && !io.in.valid){fail()}}
  def field(n:UInt)(body:UInt=>Unit):Unit={
    when(available>=n){take:=n;body(pool & ((1.U(65.W)<<n)-1.U)(63,0))}
    .otherwise{requireBits(n)}
  }
  def field(n:Int)(body:UInt=>Unit):Unit=field(n.U(5.W))(body)
  def enqueue(value:UInt):Unit={
    when(decoded>=expected){fail()}
    .otherwise{pending:=true.B;output:=value;decoded:=decoded+1.U}
  }
  def endBlock():Unit={
    when(finalBlock){when(decoded=/=expected){fail()}.otherwise{state:=alignAdler}}
    .otherwise{state:=block}
  }
  val order=VecInit(Seq(16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15).map(_.U(5.W)))
  val lenBases=VecInit(Seq(3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258).map(_.U(9.W)))
  val lenExtras=VecInit((Seq.fill(8)(0)++(1 to 5).flatMap(n=>Seq.fill(4)(n))++Seq(0)).map(_.U(4.W)))
  val distBases=VecInit(Seq(1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577).map(_.U(16.W)))
  val distExtras=VecInit((Seq.fill(4)(0)++(1 to 13).flatMap(n=>Seq(n,n))).map(_.U(4.W)))
  val readEnable=WireDefault(false.B);val readAddress=WireDefault(0.U(15.W))
  val forward=WireDefault(false.B);val forwardValue=WireDefault(0.U(8.W))
  val readAt=history.read(readAddress,readEnable)
  val fresh=RegNext(readEnable,false.B);val forwarded=RegEnable(forward,readEnable)
  val forwardedValue=RegEnable(forwardValue,readEnable);val heldRead=Reg(UInt(8.W))
  val readValue=Mux(fresh,Mux(forwarded,forwardedValue,readAt),heldRead)
  when(fresh){heldRead:=Mux(forwarded,forwardedValue,readAt)}
  val previousByte=Mux(pending,output,lastCommitted)
  // Committed history and Adler advance only on actual output delivery.
  when(io.out.fire){
    pending:=false.B;history.write(produced(14,0),output);produced:=produced+1.U;lastCommitted:=output
    // Compute the two modular sums in parallel: s2+s1+byte is congruent
    // to s2+newS1 and is below 2*65521+255. No newS1->s2 carry cascade.
    val a=adler1+&output
    val b=(adler1+&adler2)+&output
    adler1:=Mux(a>=65521.U,a-65521.U,a)(15,0)
    adler2:=Mux(b>=131042.U,b-131042.U,Mux(b>=65521.U,b-65521.U,b))(15,0)
  }
  val symbolValid=WireDefault(false.B);val symbol=WireDefault(0.U(9.W));val symbolBits=WireDefault(0.U(4.W))
  val seven=Reverse(pool(6,0));val eight=Reverse(pool(7,0));val nine=Reverse(pool(8,0))
  when(fixed){
    when(available>=7.U && seven<24.U){symbolValid:=true.B;symbol:=seven+&256.U;symbolBits:=7.U}
    .elsewhen(available>=8.U && eight>=48.U && eight<192.U){symbolValid:=true.B;symbol:=eight-48.U;symbolBits:=8.U}
    .elsewhen(available>=8.U && eight>=192.U && eight<200.U){symbolValid:=true.B;symbol:=eight-192.U+280.U;symbolBits:=8.U}
    .elsewhen(available>=9.U && nine>=400.U){symbolValid:=true.B;symbol:=nine-400.U+144.U;symbolBits:=9.U}
  }.otherwise{symbolValid:=lit.io.valid;symbol:=lit.io.symbol;symbolBits:=lit.io.used}
  def aheadRequire(n:UInt):Unit={
    when(available<n && io.inputEnded && !io.in.valid){ahead:=aheadFault}
  }
  when(state===copyStream && copyLeft>1.U){
    when(ahead===aheadEmpty){
      lit.io.query:= !fixed
      when(!fixed && lit.io.failed){ahead:=aheadFault}
      .elsewhen(symbolValid){
        when(symbol>285.U){ahead:=aheadFault}
        .elsewhen(symbol>=257.U){
          val i=(symbol-257.U)(4,0);val n=lenExtras(i);val total=symbolBits+&n
          when(available>=total){
            val suffix=(pool>>symbolBits)(4,0) & ((1.U(6.W)<<n)-1.U)(4,0)
            take:=total;lit.io.accept:= !fixed
            nextLength:=lenBases(i)+suffix;ahead:=aheadLength
          }.otherwise{aheadRequire(total)}
        }
      }.otherwise{aheadRequire(Mux(fixed,9.U,lit.io.needed))}
    }.elsewhen(ahead===aheadLength){
      dist.io.query:= !fixed
      val valid=Mux(fixed,available>=5.U,dist.io.valid)
      val s=Mux(fixed,Reverse(pool(4,0)).pad(9),dist.io.symbol)
      val used=Mux(fixed,5.U(4.W),dist.io.used)
      when(!fixed && dist.io.failed){ahead:=aheadFault}
      .elsewhen(valid){
        when(s>=30.U){ahead:=aheadFault}
        .otherwise{
          val n=distExtras(s(4,0));val total=used+&n
          when(available>=total){
            val suffix=(pool>>used)(12,0) & ((1.U(14.W)<<n)-1.U)(12,0)
            val d=distBases(s(4,0))+&suffix
            take:=total;dist.io.accept:= !fixed
            when(d===0.U || d>32768.U || d>window || d>copyEnd){ahead:=aheadFault}
            .otherwise{nextDistance:=d;ahead:=aheadMatch}
          }.otherwise{aheadRequire(total)}
        }
      }.otherwise{aheadRequire(Mux(fixed,5.U,dist.io.needed))}
    }
  }
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
        is(1.U){fixed:=true.B;state:=litDecode}
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
    is(storedByte){when(outputSpace){field(8){v=>
      enqueue(v);remaining:=remaining-1.U
      when(remaining===1.U){
        when(finalBlock){when(decoded+1.U=/=expected){fail()}.otherwise{state:=alignAdler}}
        .otherwise{state:=block}
      }
    }}}
    is(dynHeader){field(14){v=>
      nlit:=v(4,0)+&257.U;ndist:=v(9,5)+&1.U;ncode:=v(13,10)+&4.U
      for(i<-0 until 19){codeLengths(i):=0.U};cursor:=0.U;state:=dynCodeRead
      when(v(4,0)>29.U){fail()}
    }}
    is(dynCodeRead){field(3){v=>
      codeLengths(order(cursor(4,0))):=v;cursor:=cursor+1.U
      when(cursor+1.U===ncode){state:=dynCodeConfig}
    }}
    is(dynCodeConfig){
      cl.io.config.valid:=lit.io.config.ready && dist.io.config.ready
      lit.io.config.valid:=cl.io.config.ready && dist.io.config.ready
      dist.io.config.valid:=cl.io.config.ready && lit.io.config.ready
      when(cl.io.config.fire && lit.io.config.fire && dist.io.config.fire){cursor:=0.U;state:=dynCodeLoad}
    }
    is(dynCodeLoad){when(cl.io.length.fire){cursor:=cursor+1.U;when(cursor===18.U){state:=dynCodeWait}}}
    is(dynCodeWait){when(cl.io.failed){fail()}.elsewhen(cl.io.built){cursor:=0.U;previous:=0.U;state:=dynLengths}}
    is(dynLengths){
      cl.io.query:=true.B
      when(cl.io.failed){fail()}
      .elsewhen(cl.io.valid){
          val s=cl.io.symbol
          when(s<16.U){
            sendLength(s(3,0))
            when(lengthReady){
              take:=cl.io.used;cl.io.accept:=true.B;previous:=s;cursor:=cursor+1.U
              when(cursor+1.U===nlit+&ndist){state:=treesWait}
            }
          }.otherwise{
            take:=cl.io.used;cl.io.accept:=true.B
            repeatValue:=Mux(s===16.U,previous,0.U);repeatBase:=Mux(s===18.U,11.U,3.U)
            repeatExtra:=Mux(s===18.U,7.U,Mux(s===17.U,3.U,2.U));state:=dynRepeat
            when(s>18.U || (s===16.U && cursor===0.U)){fail()}
          }
      }.otherwise{requireBits(cl.io.needed)}
    }
    is(dynRepeat){field(repeatExtra){v=>
      val count=v+&repeatBase
      when(cursor+&count>nlit+&ndist){fail()}
      .otherwise{repeatLeft:=count;previous:=repeatValue;state:=repeatFill}
    }}
    is(repeatFill){
      sendLength(repeatValue)
      when(lengthReady){
        cursor:=cursor+1.U;repeatLeft:=repeatLeft-1.U
        when(repeatLeft===1.U){state:=dynLengths;when(cursor+1.U===nlit+&ndist){state:=treesWait}}
      }
    }
    is(treesWait){when(lit.io.failed || dist.io.failed){fail()}.elsewhen(lit.io.built && dist.io.built){state:=litDecode}}
    is(litDecode){
      lit.io.query:= !fixed
      when(!fixed && lit.io.failed){fail()}
      .elsewhen(symbolValid){
        when(symbol<256.U){when(outputSpace){take:=symbolBits;enqueue(symbol)}}
        .otherwise{
          when(symbol===256.U){take:=symbolBits;endBlock()}
          .elsewhen(symbol<=285.U){
            val i=(symbol-257.U)(4,0);val n=lenExtras(i);val total=symbolBits+&n
            when(available>=total){
              val suffix=(pool>>symbolBits)(4,0) & ((1.U(6.W)<<n)-1.U)(4,0)
              val nbytes=lenBases(i)+suffix
              take:=total;copyLeft:=nbytes;copyEnd:=decoded+&nbytes;state:=distDecode
            }.otherwise{requireBits(total)}
          }.otherwise{fail()}
        }
        lit.io.accept:= !fixed && take=/=0.U
      }.otherwise{requireBits(Mux(fixed,9.U,lit.io.needed))}
    }
    is(lenExtra){field(extra){v=>copyLeft:=lengthBase+v;copyEnd:=decoded+&(lengthBase+v);state:=distDecode}}
    is(distDecode){
      dist.io.query:= !fixed
      val valid=Mux(fixed,available>=5.U,dist.io.valid)
      val s=Mux(fixed,Reverse(pool(4,0)).pad(9),dist.io.symbol)
      val used=Mux(fixed,5.U(4.W),dist.io.used)
      when(!fixed && dist.io.failed){fail()}
      .elsewhen(valid){
        when(s>=30.U){fail()}
        .otherwise{
          val n=distExtras(s(4,0));val total=used+&n
          when(available>=total && outputSpace){
            val suffix=(pool>>used)(12,0) & ((1.U(14.W)<<n)-1.U)(12,0)
            val d=distBases(s(4,0))+&suffix
            take:=total;dist.io.accept:= !fixed
            when(d===0.U || d>32768.U || d>window || d>decoded){fail()}
            .otherwise{
              distance:=d;readEnable:=true.B;readAddress:=(decoded-d)(14,0)
              forward:=d===1.U;forwardValue:=previousByte;state:=copyStream
            }
          }.otherwise{requireBits(total)}
        }
      }.otherwise{requireBits(Mux(fixed,5.U,dist.io.needed))}
    }
    is(distExtra){field(extra){v=>
      val d=distanceBase+&v
      when(d===0.U || d>32768.U || d>window || d>decoded){fail()}
      .otherwise{distance:=d;state:=copyPrepare}
    }}
    is(copyPrepare){when(outputSpace){
      readEnable:=true.B;readAddress:=(decoded-distance)(14,0)
      forward:=distance===1.U;forwardValue:=previousByte;state:=copyStream
    }}
    is(copyStream){when(outputSpace){
      enqueue(readValue);copyLeft:=copyLeft-1.U
      when(copyLeft===1.U){
        ahead:=aheadEmpty
        when(ahead===aheadMatch){
          distance:=nextDistance;copyLeft:=nextLength
          copyEnd:=copyEnd+&nextLength
          readEnable:=true.B;readAddress:=(decoded+1.U-nextDistance)(14,0)
          forward:=nextDistance<=2.U
          forwardValue:=Mux(nextDistance===1.U,readValue,previousByte)
        }.elsewhen(ahead===aheadLength){copyLeft:=nextLength;copyEnd:=copyEnd+&nextLength;state:=distDecode}
        .elsewhen(ahead===aheadFault){fail()}
        .otherwise{state:=litDecode}
      }
      .otherwise{
        readEnable:=true.B;readAddress:=(decoded+1.U-distance)(14,0)
        forward:=distance<=2.U;forwardValue:=Mux(distance===1.U,readValue,previousByte)
      }
    }}
    is(alignAdler){take:=available(2,0);adlerValue:=0.U;adlerIndex:=0.U;state:=waitOutput}
    is(waitOutput){when(!pending && produced===expected){state:=adlerBytes}}
    is(adlerBytes){field(8){v=>
      val next=Cat(adlerValue(23,0),v(7,0));adlerValue:=next;adlerIndex:=adlerIndex+1.U
      when(adlerIndex===3.U){state:=finish;when(next=/=Cat(adler2,adler1)){status:=4.U}}
    }}
    is(finish){when(io.done.fire){state:=idle}}
  }
  val shifted=pool>>take;val left=available-take
  when(take=/=0.U || io.in.fire){
    pool:=shifted;available:=left
    when(io.in.fire){
      assert(io.in.bits.bytes===1.U || io.in.bits.bytes===2.U,"FAST_INFLATE_INPUT_COUNT")
      val bytes=Mux(io.in.bits.bytes===1.U,io.in.bits.data & 255.U,io.in.bits.data)
      pool:=shifted | (bytes.pad(64)<<(left(5,0)))(63,0)
      available:=left+(io.in.bits.bytes<<3);consumed:=consumed+io.in.bits.bytes
    }
  }
  // Failure wins over a last-copy/stored-byte state transition in the same
  // cycle, but never retracts an already public pending output.
  when(fault){status:=faultCode;state:=finish}
  assert(decoded>=produced && decoded-produced<=1.U,"FAST_INFLATE_SKID_COUNTS")
  when(state===copyStream){assert(copyEnd===decoded+&copyLeft,"FAST_INFLATE_COPY_END")}
  when(io.config.fire){
    expected:=io.config.bits.outputBytes;decoded:=0.U;produced:=0.U;consumed:=0.U;status:=0.U
    pool:=0.U;available:=0.U;pending:=false.B;adler1:=1.U;adler2:=0.U;state:=cmf;ahead:=aheadEmpty
  }
}
