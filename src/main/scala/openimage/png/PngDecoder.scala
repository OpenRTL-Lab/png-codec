// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: PngDecoder.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._

class PngDescriptor extends Bundle {
  val tag=UInt(32.W);val imageWidth=UInt(14.W);val imageHeight=UInt(14.W)
  val colorType=UInt(3.W);val bitDepth=UInt(5.W)
  val paletteBytes=UInt(10.W);val transparencyBytes=UInt(9.W)
}

/** 完整PNG文件解析、连续IDAT、原始行及必要元数据，不隐式转换颜色。 */
class PngDecoder extends Module {
  val io=IO(new Bundle {
    val cmd=Flipped(Decoupled(new PngCommand));val in=Flipped(Decoupled(new StreamBeat))
    val descriptor=Decoupled(new PngDescriptor);val metadata=Decoupled(new StreamBeat)
    val out=Decoupled(new StreamBeat);val done=Decoupled(new PngCompletion)
  })
  val idle :: commandStart :: signature :: header :: checkBounds :: checkHeader :: payload :: crcIn :: commitChunk :: sizeRow :: sizeTotals :: sizeJoin :: sizeFilter :: describe :: sendMeta :: waitMeta :: setup :: waitEnd :: drain :: finish :: Nil=Enum(20)
  val state=RegInit(idle);val cmd=Reg(new PngCommand);val status=RegInit(0.U(3.W))
  val w=RegInit(0.U(14.W));val h=RegInit(0.U(14.W));val color=RegInit(0.U(3.W));val depth=RegInit(0.U(5.W))
  val plen=RegInit(0.U(10.W));val tlen=RegInit(0.U(9.W))
  val rowBytes=Reg(UInt(17.W));val bpp=Reg(UInt(4.W));val filteredBytes=Reg(UInt(32.W));val rawBytes=Reg(UInt(32.W))
  val sampleBits=Reg(UInt(9.W));val rawLow=Reg(UInt(24.W));val rawHigh=Reg(UInt(24.W))
  val pixelTotal=Reg(UInt(28.W))
  val meta=Seq.fill(8)(Mem(128,UInt(8.W)));val ihdr=Reg(Vec(13,UInt(8.W)))
  val metaValid=RegInit(false.B);val metaData=Reg(UInt(64.W))
  val metaKeep=Reg(UInt(8.W));val metaLast=Reg(Bool())
  val length=Reg(UInt(32.W));val kind=Reg(UInt(32.W));val pos=RegInit(0.U(32.W))
  val payloadLeft=Reg(UInt(32.W));val chunkEnd=Reg(UInt(33.W))
  val hi=RegInit(0.U(4.W));val sigIndex=RegInit(0.U(3.W));val crcIndex=RegInit(0.U(2.W))
  val crc=RegInit("hffffffff".U(32.W));val storedCrc=Reg(UInt(32.W))
  val fileRead=RegInit(0.U(32.W));val metaIndex=RegInit(0.U(11.W))
  val sawHeader=RegInit(false.B);val sawPalette=RegInit(false.B);val sawTrns=RegInit(false.B)
  val sawIdat=RegInit(false.B);val idatClosed=RegInit(false.B)
  val infoPending=RegInit(false.B);val zFinished=RegInit(false.B);val initialized=RegInit(false.B)
  val needFilter=RegInit(true.B);val filter=Reg(UInt(3.W));val inputCol=RegInit(0.U(17.W));val outputCol=RegInit(0.U(17.W))
  val reserved=RegInit(0.U(32.W));val sent=RegInit(0.U(32.W));val outputLast=RegInit(false.B)
  val error=WireDefault(0.U(3.W));val cancel=state===idle || state===commandStart || state===drain || state===finish
  val working= !cancel
  val input=Module(new FlexByteInput)
  val inflate=withReset(reset.asBool || cancel){Module(new FastInflate)}
  val row=withReset(reset.asBool || cancel){Module(new PngRowTransform)}
  val rowOutput=withReset(reset.asBool || cancel){Module(new Queue(new ByteBeat,2,pipe=false,flow=false))}
  rowOutput.io.enq<>row.io.out
  val reconstructed=rowOutput.io.deq
  val pack=Module(new BytePacker)
  input.io.in<>io.in
  input.io.in.valid:=io.in.valid && state=/=idle && state=/=commandStart && state=/=finish
  io.in.ready:=input.io.in.ready && state=/=idle && state=/=commandStart && state=/=finish
  input.io.start:=false.B;input.io.length:=cmd.inputBytes;input.io.discard:=state===drain
  input.io.out.ready:=false.B
  input.io.single:=state=/=payload || kind==="h49484452".U || payloadLeft===1.U
  val published=Module(new Queue(new StreamBeat,2,pipe=false,flow=false))
  published.io.enq<>pack.io.out;io.out<>published.io.deq
  io.metadata.valid:=metaValid;io.metadata.bits.data:=metaData
  io.metadata.bits.keep:=metaKeep;io.metadata.bits.last:=metaLast
  when(io.metadata.fire){metaValid:=false.B}
  pack.io.clear:=false.B;pack.io.in.valid:=false.B;pack.io.in.bits.data:=0.U;pack.io.in.bits.last:=false.B
  io.cmd.ready:=state===idle
  io.descriptor.valid:=infoPending
  io.descriptor.bits.tag:=cmd.tag;io.descriptor.bits.imageWidth:=w;io.descriptor.bits.imageHeight:=h
  io.descriptor.bits.colorType:=color;io.descriptor.bits.bitDepth:=depth
  io.descriptor.bits.paletteBytes:=plen;io.descriptor.bits.transparencyBytes:=tlen
  when(io.descriptor.fire){infoPending:=false.B}
  io.done.valid:=state===finish
  io.done.bits.tag:=cmd.tag;io.done.bits.status:=status;io.done.bits.inputBytes:=input.io.received
  io.done.bits.outputBytes:=sent;io.done.bits.pixels:=Mux(status===0.U,pixelTotal,0.U)
  io.done.bits.imageWidth:=w;io.done.bits.imageHeight:=h;io.done.bits.colorType:=color;io.done.bits.bitDepth:=depth
  io.done.bits.paletteBytes:=plen;io.done.bits.transparencyBytes:=tlen
  inflate.io.config.valid:=state===setup;inflate.io.config.bits.outputBytes:=filteredBytes
  inflate.io.in.valid:=false.B;inflate.io.in.bits:=input.io.out.bits;inflate.io.inputEnded:=idatClosed
  inflate.io.out.ready:=false.B;inflate.io.done.ready:=working
  row.io.config.valid:=state===setup;row.io.config.bits.rowBytes:=rowBytes
  row.io.config.bits.rows:=h;row.io.config.bits.bytesPerPixel:=bpp;row.io.config.bits.inverse:=true.B
  row.io.in.valid:=false.B;row.io.in.bits.data:=inflate.io.out.bits.data;row.io.in.bits.filter:=filter
  reconstructed.ready:=false.B
  val IHDR="h49484452".U;val PLTE="h504c5445".U;val TRNS="h74524e53".U
  val IDAT="h49444154".U;val IEND="h49454e44".U
  val wantByte=state===signature || state===header || state===payload || state===crcIn
  val byte=input.io.out.bits.data(7,0);val byte2=input.io.out.bits.data(15,8)
  val inputCount=input.io.out.bits.bytes
  val metaWrites=Wire(Vec(2,new Bundle{val valid=Bool();val address=UInt(10.W);val data=UInt(8.W)}))
  for(i<-0 until 2){metaWrites(i).valid:=false.B;metaWrites(i).address:=0.U;metaWrites(i).data:=0.U}
  for(bank<-0 until 8){
    val a=metaWrites(0);val b=metaWrites(1)
    when(a.valid && a.address(2,0)===bank.U){meta(bank).write(a.address(9,3),a.data)}
    .elsewhen(b.valid && b.address(2,0)===bank.U){meta(bank).write(b.address(9,3),b.data)}
  }
  val signatureBytes=VecInit(Seq(137,80,78,71,13,10,26,10).map(_.U(8.W)))
  def nextHeader():Unit={state:=header;hi:=0.U;crc:="hffffffff".U}
  def beginPayload():Unit={pos:=0.U;payloadLeft:=length;state:=Mux(length===0.U,crcIn,payload);crcIndex:=0.U;storedCrc:=0.U}
  when(input.io.out.fire){fileRead:=fileRead+inputCount}
  when(io.out.fire){sent:=sent+PopCount(io.out.bits.keep);when(io.out.bits.last){outputLast:=true.B}}
  when(inflate.io.done.fire){
    when(inflate.io.done.bits=/=0.U){error:=inflate.io.done.bits}
    .otherwise{zFinished:=true.B}
  }
  // inflate与解析器并行：块CRC/零长IDAT不会清除字典或位池。
  when(working && initialized){
    when(needFilter){
      inflate.io.out.ready:=true.B
      when(inflate.io.out.fire){
        when(inflate.io.out.bits.data>4.U){error:=3.U}
        .otherwise{filter:=inflate.io.out.bits.data;needFilter:=false.B;inputCol:=0.U}
      }
    }.otherwise{
      row.io.in.valid:=inflate.io.out.valid;inflate.io.out.ready:=row.io.in.ready
      when(row.io.in.fire){inputCol:=inputCol+1.U;when(inputCol+1.U===rowBytes){needFilter:=true.B}}
    }
    val lastRaw=outputCol+1.U===rowBytes
    val unused=(0.U(3.W)-(w*depth)(2,0))(2,0)
    val lowMask=((1.U(9.W)<<unused)-1.U)(7,0)
    val normalized=Mux(depth<8.U && lastRaw,reconstructed.bits.data & ~lowMask,reconstructed.bits.data)
    val badIndex=WireDefault(false.B)
    when(color===3.U){
      for(bits<-Seq(1,2,4,8);i<-0 until 8/bits){
        when(depth===bits.U && outputCol*(8/bits).U+i.U<w &&
          reconstructed.bits.data(7-i*bits,8-(i+1)*bits)>=plen/3.U){badIndex:=true.B}
      }
    }
    when(reconstructed.valid){
      when(badIndex){error:=3.U}
      .elsewhen(reserved>=cmd.outputLimit){error:=6.U}
      .otherwise{
        pack.io.in.valid:=true.B;pack.io.in.bits.data:=normalized;pack.io.in.bits.last:=reconstructed.bits.last
        reconstructed.ready:=pack.io.in.ready
      }
    }
    when(reconstructed.fire){reserved:=reserved+1.U;outputCol:=Mux(lastRaw,0.U,outputCol+1.U)}
  }
  switch(state){
    is(commandStart){
      sigIndex:=0.U;fileRead:=0.U
      sawHeader:=false.B;sawPalette:=false.B;sawTrns:=false.B;sawIdat:=false.B;idatClosed:=false.B
      plen:=0.U;tlen:=0.U;w:=0.U;h:=0.U;color:=0.U;depth:=0.U;infoPending:=false.B
      zFinished:=false.B;initialized:=false.B;needFilter:=true.B;inputCol:=0.U;outputCol:=0.U
      reserved:=0.U;sent:=0.U;outputLast:=false.B;metaValid:=false.B
      val valid=cmd.inputBytes=/=0.U && cmd.inputBytes<="h40000000".U &&
        cmd.outputLimit=/=0.U && cmd.outputLimit<="h40000000".U
      input.io.start:=true.B;input.io.length:=Mux(valid,cmd.inputBytes,0.U)
      state:=signature
      when(!valid){status:=1.U;state:=finish}
    }
    is(signature){
      input.io.out.ready:=true.B
      when(input.io.out.fire){
        when(byte=/=signatureBytes(sigIndex)){error:=3.U}
        sigIndex:=sigIndex+1.U;when(sigIndex===7.U){nextHeader()}
      }
    }
    is(header){
      input.io.out.ready:=true.B
      when(input.io.out.fire){
        when(hi<4.U){length:=Cat(length(23,0),byte)}
        .otherwise{kind:=Cat(kind(23,0),byte);crc:=Checksums.crc32(crc,byte)}
        hi:=hi+1.U;when(hi===7.U){state:=checkBounds}
      }
    }
    is(checkBounds){chunkEnd:=fileRead+&length;state:=checkHeader}
    is(checkHeader){
      val alphabet=WireDefault(true.B)
      for(i<-0 until 4){val c=kind(31-i*8,24-i*8);when(!((c>=65.U && c<=90.U)||(c>=97.U && c<=122.U))){alphabet:=false.B}}
      beginPayload()
      when(!alphabet || kind(13) || length(31) || chunkEnd+&4.U>cmd.inputBytes || (!sawHeader && kind=/=IHDR)){error:=3.U}
      .elsewhen(kind==="h6163544c".U || kind==="h6663544c".U || kind==="h66644154".U){error:=2.U}
      .elsewhen(kind===IHDR){when(sawHeader || length=/=13.U){error:=3.U}}
      .elsewhen(kind===PLTE){
        // High length bits are rejected by the explicit <=768 check; only the
        // bounded ten-bit value needs division for a legal palette.
        when(sawPalette || sawTrns || sawIdat || color===0.U || color===4.U || length===0.U || length>768.U || length(9,0)%3.U=/=0.U ||
          (color===3.U && length(9,0)/3.U>(1.U(17.W)<<depth))){error:=3.U}
      }
      .elsewhen(kind===TRNS){
        val good=MuxLookup(color,false.B)(Seq(0.U->(length===2.U),2.U->(length===6.U),3.U->(sawPalette && length=/=0.U && length<=plen/3.U)))
        when(sawTrns || sawIdat || !good){error:=3.U}
      }
      .elsewhen(kind===IDAT){
        when(idatClosed || (color===3.U && !sawPalette)){error:=3.U}
        .elsewhen(!sawIdat){sawIdat:=true.B;infoPending:=true.B;state:=describe;metaIndex:=0.U}
      }
      .elsewhen(kind===IEND){when(length=/=0.U || !sawIdat){error:=3.U}}
      .elsewhen(!kind(29)){error:=2.U}
      when(sawIdat && kind=/=IDAT){idatClosed:=true.B}
    }
    is(describe){when(!infoPending){state:=sendMeta}}
    is(sendMeta){
      val total=plen+&tlen
      when(metaIndex===total){state:=waitMeta}
      .elsewhen(!metaValid || io.metadata.ready){
        val n=Mux(total-metaIndex>=8.U,8.U(4.W),(total-metaIndex)(3,0))
        metaData:=Cat((0 until 8).reverse.map(i=>Mux(i.U<n,meta(i)(metaIndex(9,3)),0.U(8.W))))
        metaKeep:=((1.U(9.W)<<n)-1.U)(7,0);metaLast:=metaIndex+n===total;metaValid:=true.B
        metaIndex:=metaIndex+n
      }
    }
    is(waitMeta){when(!metaValid){state:=setup}}
    is(setup){when(inflate.io.config.fire && row.io.config.fire){initialized:=true.B;beginPayload()}}
    is(payload){
      when(kind===IDAT && !zFinished){inflate.io.in.valid:=input.io.out.valid;input.io.out.ready:=inflate.io.in.ready}
      .otherwise{input.io.out.ready:=true.B}
      when(input.io.out.fire){
        val crc1=Checksums.crc32(crc,byte)
        crc:=Mux(inputCount===2.U,Checksums.crc32(crc1,byte2),crc1)
        when(kind===IHDR){ihdr(pos(3,0)):=byte}
        for(i<-0 until 2){
          val p=pos+i.U;val value=if(i==0)byte else byte2
          when(i.U<inputCount && (kind===PLTE || kind===TRNS)){
            val keyMask=Mux(depth<8.U,((1.U(17.W)<<depth)-1.U)(7,0),255.U(8.W))
            val normalized=Mux((color===0.U || color===2.U) && depth<16.U,Mux(p(0),value&keyMask,0.U),value)
            metaWrites(i).valid:=true.B;metaWrites(i).address:=Mux(kind===PLTE,p,plen+p)
            metaWrites(i).data:=Mux(kind===PLTE,value,normalized)
          }
        }
        pos:=pos+inputCount;payloadLeft:=payloadLeft-inputCount
        when(payloadLeft===inputCount){state:=crcIn;crcIndex:=0.U;storedCrc:=0.U}
      }
    }
    is(crcIn){
      input.io.out.ready:=true.B
      when(input.io.out.fire){
        val next=Cat(storedCrc(23,0),byte);storedCrc:=next;crcIndex:=crcIndex+1.U
        when(crcIndex===3.U){when(next=/=(crc^"hffffffff".U)){error:=4.U}.otherwise{state:=commitChunk}}
      }
    }
    is(commitChunk){
      nextHeader()
      when(kind===IHDR){
        val width=Cat(ihdr(0),ihdr(1),ihdr(2),ihdr(3));val height=Cat(ihdr(4),ihdr(5),ihdr(6),ihdr(7))
        val t=ihdr(9);val b=ihdr(8)
        val valid=((t===0.U || t===3.U)&&(b===1.U || b===2.U || b===4.U || b===8.U)) ||
          ((t===0.U || t===2.U || t===4.U || t===6.U)&&(b===8.U || b===16.U))
        val channels=MuxLookup(t,1.U(3.W))(Seq(2.U->3.U,4.U->2.U,6.U->4.U))
        when(width===0.U || height===0.U || !valid){error:=3.U}
        .elsewhen(width>8192.U || height>8192.U || ihdr(10)=/=0.U || ihdr(11)=/=0.U || ihdr(12)=/=0.U){error:=2.U}
        .otherwise{
          sawHeader:=true.B;w:=width;h:=height;color:=t;depth:=b
          sampleBits:=channels*b;state:=sizeRow
        }
      }
      when(kind===PLTE){plen:=length;sawPalette:=true.B}
      when(kind===TRNS){tlen:=length;sawTrns:=true.B}
      when(kind===IEND){
        when(fileRead=/=cmd.inputBytes){error:=3.U}.otherwise{state:=waitEnd}
      }
    }
    is(sizeRow){rowBytes:=(w*sampleBits+&7.U)>>3;bpp:=(sampleBits+&7.U)>>3;state:=sizeTotals}
    is(sizeTotals){
      rawLow:=rowBytes*h(6,0);rawHigh:=rowBytes*h(13,7);pixelTotal:=w*h;state:=sizeJoin
    }
    is(sizeJoin){rawBytes:=rawLow+&(rawHigh<<7);state:=sizeFilter}
    is(sizeFilter){filteredBytes:=rawBytes+&h;nextHeader()}
    is(waitEnd){
      when(zFinished && pack.io.empty && published.io.count===0.U && rowOutput.io.count===0.U && row.io.config.ready){
        when(reserved=/=rawBytes || sent=/=rawBytes || !outputLast){error:=3.U}
        .otherwise{state:=finish}
      }
    }
    is(drain){
      when(!pack.io.out.valid && published.io.count===0.U){pack.io.clear:=true.B}
      when(input.io.closed && !pack.io.out.valid && published.io.count===0.U && !io.metadata.valid && !infoPending){state:=finish}
    }
    is(finish){when(io.done.fire){state:=idle}}
  }
  when(working && wantByte && input.io.closed && !input.io.out.valid){error:=3.U}
  when(working && input.io.failed){error:=5.U}
  when(working && error=/=0.U){status:=error;state:=drain}
  when(io.cmd.fire){
    cmd:=io.cmd.bits;status:=0.U;state:=commandStart
  }
}
