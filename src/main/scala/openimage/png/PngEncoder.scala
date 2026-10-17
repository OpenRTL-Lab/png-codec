// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: PngEncoder.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._

/** 15种格式、元数据、五滤波和STORED/FIXED完整PNG，缓存有界而非整图。 */
class PngEncoder extends Module {
  val io=IO(new Bundle {
    val cmd=Flipped(Decoupled(new PngCommand))
    val metadata=Flipped(Decoupled(new StreamBeat))
    val in=Flipped(Decoupled(new StreamBeat));val out=Decoupled(new StreamBeat)
    val done=Decoupled(new PngCompletion)
  })
  val idle :: commandDecode :: commandRow :: commandTotals :: commandJoin :: commandCheck :: metadata :: setup :: signature :: head :: body :: crcOut :: fill :: waitPack :: drain :: finish :: Nil=Enum(16)
  val state=RegInit(idle);val cmd=Reg(new PngCommand);val status=RegInit(0.U(3.W))
  val sampleBits=Reg(UInt(9.W));val rowCalculated=Reg(UInt(21.W))
  val rawExpected=Reg(UInt(35.W));val commandFieldsValid=Reg(Bool())
  val rawLow=Reg(UInt(28.W));val rawHigh=Reg(UInt(28.W));val pixelTotal=Reg(UInt(28.W))
  val rowBytes=Reg(UInt(17.W));val bpp=Reg(UInt(4.W));val filteredBytes=Reg(UInt(32.W))
  val inputColumn=RegInit(0.U(17.W));val outputColumn=RegInit(0.U(17.W))
  // Two independently owned banks, each with even/odd byte lanes.
  val buffers=Seq.fill(2)(Seq.fill(2)(SyncReadMem(1024,UInt(8.W))))
  val writeSequence=RegInit(0.U(2.W));val readSequence=RegInit(0.U(2.W))
  val buffered=RegInit(0.U(14.W));val firstBank=RegInit(true.B)
  val bankLength=Reg(Vec(2,UInt(14.W)));val bankFinal=Reg(Vec(2,Bool()))
  val bankFull=(writeSequence-readSequence)===2.U
  val bankAvailable=writeSequence=/=readSequence
  val threshold=Mux(firstBank,Mux(cmd.fixed,6.U(14.W),16.U(14.W)),2048.U(14.W))
  val needFilter=RegInit(true.B)
  val kind=RegInit(0.U(3.W)) // IHDR,PLTE,tRNS,IDAT,IEND
  val length=Reg(UInt(32.W));val position=RegInit(0.U(32.W))
  val crc=RegInit("hffffffff".U(32.W))
  val sent=RegInit(0.U(32.W));val reserved=RegInit(0.U(32.W))
  val error=WireDefault(0.U(3.W))
  val preparing=state===commandDecode || state===commandRow || state===commandTotals || state===commandJoin || state===commandCheck
  val cancel=state===idle || preparing || state===drain || state===finish
  val pixels=Module(new ByteInput);val metas=Module(new MetadataStore)
  val row=withReset(reset.asBool || cancel){Module(new PngRowTransform)}
  val zlib=withReset(reset.asBool || cancel){Module(new WideZlibEncoder)}
  val rowOutput=withReset(reset.asBool || cancel){Module(new Queue(new ByteBeat,2,pipe=false,flow=false))}
  rowOutput.io.enq<>row.io.out
  val filtered=rowOutput.io.deq
  val pack=Module(new WideBytePacker)
  // Published output survives errors. Only global reset cancels this queue.
  val published=Module(new Queue(new StreamBeat,2,pipe=false,flow=false))
  published.io.enq<>pack.io.out;io.out<>published.io.deq
  pack.io.clear:=false.B;pack.io.in.valid:=false.B;pack.io.in.bits.data:=0.U
  pack.io.in.bits.bytes:=1.U;pack.io.in.bits.last:=false.B
  pixels.io.start:=false.B;pixels.io.length:=cmd.inputBytes;pixels.io.discard:=state===drain
  metas.io.start:=false.B;metas.io.length:=cmd.paletteBytes+&cmd.transparencyBytes
  metas.io.discard:=state===drain
  val allowPixels=state=/=idle && !preparing && state=/=metadata && state=/=finish
  pixels.io.in.valid:=io.in.valid && allowPixels;pixels.io.in.bits:=io.in.bits
  io.in.ready:=pixels.io.in.ready && allowPixels
  metas.io.in.valid:=io.metadata.valid && (state===metadata || state===drain)
  metas.io.in.bits:=io.metadata.bits;io.metadata.ready:=metas.io.in.ready && (state===metadata || state===drain)
  val metaPosition=Mux(state===body,Mux(pack.io.in.fire,position+1.U,position),0.U)
  metas.io.readAddress:=(metaPosition+Mux(kind===2.U,cmd.paletteBytes,0.U))(9,0)
  val metadataByte=RegNext(metas.io.readData)
  io.cmd.ready:=state===idle
  io.done.valid:=state===finish
  io.done.bits.tag:=cmd.tag;io.done.bits.status:=status
  io.done.bits.inputBytes:=pixels.io.received;io.done.bits.outputBytes:=sent
  io.done.bits.pixels:=Mux(status===0.U,pixelTotal,0.U)
  io.done.bits.imageWidth:=cmd.imageWidth;io.done.bits.imageHeight:=cmd.imageHeight
  io.done.bits.colorType:=cmd.colorType;io.done.bits.bitDepth:=cmd.bitDepth
  io.done.bits.paletteBytes:=cmd.paletteBytes;io.done.bits.transparencyBytes:=cmd.transparencyBytes
  row.io.config.valid:=state===setup;row.io.config.bits.rowBytes:=rowBytes
  row.io.config.bits.rows:=cmd.imageHeight;row.io.config.bits.bytesPerPixel:=bpp;row.io.config.bits.inverse:=false.B
  zlib.io.config.valid:=state===setup;zlib.io.config.bits.inputBytes:=filteredBytes;zlib.io.config.bits.fixed:=cmd.fixed
  val working=state=/=idle && !preparing && state=/=metadata && state=/=setup && state=/=drain && state=/=finish
  val badSample=WireDefault(false.B)
  val lastRaw=inputColumn===rowBytes-1.U
  val unused=(0.U(3.W)-(cmd.imageWidth*cmd.bitDepth)(2,0))(2,0)
  val lowMask=((1.U(9.W)<<unused)-1.U)(7,0)
  when(cmd.bitDepth<8.U && lastRaw && (pixels.io.out.bits.data&lowMask)=/=0.U){badSample:=true.B}
  val entries=cmd.paletteBytes/3.U
  when(cmd.colorType===3.U){
    for(bits <- Seq(1,2,4,8);i <- 0 until 8/bits){
      when(cmd.bitDepth===bits.U && inputColumn*(8/bits).U+i.U<cmd.imageWidth &&
        pixels.io.out.bits.data(7-i*bits,8-(i+1)*bits)>=entries){badSample:=true.B}
    }
  }
  row.io.in.valid:=pixels.io.out.valid && working && !badSample
  row.io.in.bits.data:=pixels.io.out.bits.data;row.io.in.bits.filter:=cmd.filter
  pixels.io.out.ready:=working && (row.io.in.ready || badSample)
  when(pixels.io.out.fire){inputColumn:=Mux(lastRaw,0.U,inputColumn+1.U);when(badSample){error:=3.U}}
  zlib.io.in.valid:=working && (needFilter || filtered.valid)
  zlib.io.in.bits.data:=Mux(needFilter,cmd.filter.pad(8),filtered.bits.data)
  zlib.io.in.bits.last := !needFilter && filtered.bits.last
  filtered.ready:=working && !needFilter && zlib.io.in.ready
  when(zlib.io.in.fire && needFilter){needFilter:=false.B}
  when(filtered.fire){
    outputColumn:=outputColumn+1.U
    when(outputColumn===rowBytes-1.U){outputColumn:=0.U;needFilter:=true.B}
  }
  zlib.io.out.ready:=working && !bankFull && buffered+&zlib.io.out.bits.bytes<=threshold
  when(zlib.io.out.fire){
    assert(!buffered(0),"PNG_BANK_ALIGNMENT")
    for(bank<-0 until 2;lane<-0 until 2) {
      when(writeSequence(0)===bank.U && lane.U<zlib.io.out.bits.bytes) {
        buffers(bank)(lane).write(buffered(10,1),zlib.io.out.bits.data(8*lane+7,8*lane))
      }
    }
    val next=buffered+zlib.io.out.bits.bytes
    buffered:=next
    when(next===threshold || zlib.io.out.bits.last){
      bankLength(writeSequence(0)):=next;bankFinal(writeSequence(0)):=zlib.io.out.bits.last
      writeSequence:=writeSequence+1.U;buffered:=0.U;firstBank:=false.B
    }
  }
  val readEnable=WireDefault(false.B);val readIndex=WireDefault(0.U(10.W))
  val readWords=VecInit(buffers.zipWithIndex.map { case(lanes,bank)=>
    VecInit(lanes.map(_.read(readIndex,readEnable && readSequence(0)===bank.U))).asUInt
  })
  val readData=readWords(readSequence(0))
  val freshRead=RegNext(readEnable,false.B);val heldRead=Reg(UInt(16.W))
  when(freshRead) { heldRead:=readData }
  val payloadWord=Mux(freshRead,readData,heldRead)
  def beByte(v:UInt,i:UInt):UInt=(v>>((3.U-i(1,0))<<3))(7,0)
  def beginChunk(k:UInt,n:UInt):Unit={kind:=k;length:=n;position:=0.U;crc:="hffffffff".U;state:=head}
  def afterNonData():Unit={
    when(kind===0.U && cmd.paletteBytes=/=0.U){beginChunk(1.U,cmd.paletteBytes)}
    .elsewhen(kind<=1.U && cmd.transparencyBytes=/=0.U){beginChunk(2.U,cmd.transparencyBytes)}
    .otherwise{state:=fill}
  }
  val sig=VecInit(Seq(137,80,78,71,13,10,26,10).map(_.U(8.W)))
  val types=VecInit(Seq("IHDR","PLTE","tRNS","IDAT","IEND").map(s=>VecInit(s.map(_.toInt.U(8.W)).toSeq)))
  val byte=WireDefault(0.U(8.W));val byteHigh=WireDefault(0.U(8.W))
  val requested=WireDefault(1.U(2.W))
  val generate=WireDefault(false.B);val finalOutput=WireDefault(false.B)
  when(state===signature){generate:=true.B;byte:=sig(position(2,0))}
  when(state===head){generate:=true.B;byte:=Mux(position<4.U,beByte(length,position),types(kind)(position(1,0)))}
  when(state===body){
    generate:=true.B
    when(kind===0.U){
      when(position<4.U){byte:=beByte(cmd.imageWidth.pad(32),position)}
      .elsewhen(position<8.U){byte:=beByte(cmd.imageHeight.pad(32),position)}
      .elsewhen(position===8.U){byte:=cmd.bitDepth}
      .elsewhen(position===9.U){byte:=cmd.colorType}
    }.elsewhen(kind===1.U || kind===2.U){
      byte:=metadataByte
    }.elsewhen(kind===3.U){
      byte:=payloadWord(7,0);byteHigh:=payloadWord(15,8)
      requested:=Mux(position+1.U<length,2.U,1.U)
    }
  }
  when(state===crcOut){generate:=true.B;byte:=beByte(~crc,position);finalOutput:=kind===4.U && position===3.U}
  pack.io.in.valid:=generate && reserved<cmd.outputLimit
  pack.io.in.bits.data:=Cat(byteHigh,byte)
  pack.io.in.bits.bytes:=Mux(cmd.outputLimit-reserved<requested,1.U,requested)
  pack.io.in.bits.last:=finalOutput
  when(generate && reserved>=cmd.outputLimit){error:=6.U}
  when(pack.io.in.fire){
    val used=pack.io.in.bits.bytes
    reserved:=reserved+used;position:=position+used
    when(state===signature && position===7.U){beginChunk(0.U,13.U)}
    when(state===head){
      when(position>=4.U){crc:=Checksums.crc32(crc,byte)}
      when(position===7.U){
        position:=0.U;state:=body
        when(length===0.U){state:=crcOut}
        .elsewhen(kind===3.U){readEnable:=true.B;readIndex:=0.U}
      }
    }
    when(state===body){
      val once=Checksums.crc32(crc,byte)
      crc:=Mux(used===2.U,Checksums.crc32(once,byteHigh),once)
      when(position+used===length){position:=0.U;state:=crcOut}
      .elsewhen(kind===3.U){readEnable:=true.B;readIndex:=(position+used)(10,1)}
    }
    when(state===crcOut && position===3.U){
      position:=0.U
      when(kind===4.U){state:=waitPack}
      .elsewhen(kind===3.U){
        readSequence:=readSequence+1.U
        when(bankFinal(readSequence(0))){beginChunk(4.U,0.U)}.otherwise{state:=fill}
      }.otherwise{afterNonData()}
    }
  }
  when(state===fill && bankAvailable) { beginChunk(3.U,bankLength(readSequence(0))) }
  when(state===setup && row.io.config.ready && zlib.io.config.ready){state:=signature;position:=0.U}
  when(io.out.fire){sent:=sent+PopCount(io.out.bits.keep)}
  when(state===waitPack && pack.io.empty && published.io.count===0.U){state:=finish}
  when(state===metadata && metas.io.acceptedGood){
    for(i<-0 until 8) when(i.U<PopCount(metas.io.checkedBeat.keep)) {
      val index=metas.io.received+i.U
      val keyOffset=index-cmd.paletteBytes
      val value=metas.io.checkedBeat.data(8*i+7,8*i)
      when(cmd.transparencyBytes=/=0.U && index>=cmd.paletteBytes &&
        cmd.colorType=/=3.U && !keyOffset(0) && cmd.bitDepth<16.U && value=/=0.U){error:=3.U}
      when(cmd.transparencyBytes=/=0.U && index>=cmd.paletteBytes &&
        cmd.colorType===0.U && keyOffset===1.U && cmd.bitDepth<8.U &&
        value>=(1.U(17.W)<<cmd.bitDepth)){error:=3.U}
    }
  }
  when(state===metadata && metas.io.closed){state:=setup}
  when(state=/=idle && !preparing && state=/=finish && (pixels.io.failed || metas.io.failed)){error:=5.U}
  when(error=/=0.U && status===0.U && state=/=idle && state=/=finish){status:=error;state:=drain}
  when(state===drain && pixels.io.closed && metas.io.closed && !pack.io.out.valid && published.io.count===0.U){pack.io.clear:=true.B;state:=finish}
  when(io.done.fire){state:=idle}
  // External command ports terminate at cmd registers. Derived multiplications,
  // validation and ByteInput startup are separated by real register boundaries.
  when(state===commandDecode){
    inputColumn:=0.U;outputColumn:=0.U;needFilter:=true.B
    buffered:=0.U;writeSequence:=0.U;readSequence:=0.U;firstBank:=true.B
    sent:=0.U;reserved:=0.U;position:=0.U;pack.io.clear:=true.B
    val c=cmd
    val components=MuxLookup(c.colorType,0.U(4.W))(Seq(0.U->1.U,3.U->1.U,2.U->3.U,4.U->2.U,6.U->4.U))
    val legal=((c.colorType===0.U || c.colorType===3.U) &&
      (c.bitDepth===1.U || c.bitDepth===2.U || c.bitDepth===4.U || c.bitDepth===8.U)) ||
      ((c.colorType===0.U || c.colorType===2.U || c.colorType===4.U || c.colorType===6.U) &&
      (c.bitDepth===8.U || c.bitDepth===16.U))
    val entries=c.paletteBytes/3.U
    val paletteOK=(c.paletteBytes===0.U || (c.paletteBytes<=768.U && c.paletteBytes%3.U===0.U)) &&
      Mux(c.colorType===3.U,c.paletteBytes=/=0.U && entries<=(1.U(17.W)<<c.bitDepth),
        c.colorType===2.U || c.colorType===6.U || c.paletteBytes===0.U)
    val trnsOK=c.transparencyBytes===0.U ||
      (c.colorType===0.U && c.transparencyBytes===2.U) ||
      (c.colorType===2.U && c.transparencyBytes===6.U) ||
      (c.colorType===3.U && c.transparencyBytes<=entries)
    commandFieldsValid:=c.imageWidth>=1.U && c.imageWidth<=8192.U && c.imageHeight>=1.U && c.imageHeight<=8192.U &&
      c.inputBytes>=1.U && c.inputBytes<="h40000000".U && c.outputLimit>=1.U && c.outputLimit<="h40000000".U &&
      legal && c.filter<=4.U && paletteOK && trnsOK
    sampleBits:=components*c.bitDepth
    pixelTotal:=c.imageWidth*c.imageHeight
    state:=commandRow
  }
  when(state===commandRow){
    val bytes=(cmd.imageWidth*sampleBits+&7.U)>>3
    rowCalculated:=bytes;rowBytes:=bytes;bpp:=(sampleBits+&7.U)>>3
    state:=commandTotals
  }
  when(state===commandTotals){
    rawLow:=rowCalculated*cmd.imageHeight(6,0)
    rawHigh:=rowCalculated*cmd.imageHeight(13,7)
    state:=commandJoin
  }
  when(state===commandJoin){
    rawExpected:=rawLow+&(rawHigh<<7)
    state:=commandCheck
  }
  when(state===commandCheck){
    filteredBytes:=rawExpected+&cmd.imageHeight
    val valid=commandFieldsValid && rawExpected===cmd.inputBytes
    pixels.io.start:=true.B;metas.io.start:=true.B
    pixels.io.length:=Mux(valid,cmd.inputBytes,0.U)
    metas.io.length:=Mux(valid,cmd.paletteBytes+&cmd.transparencyBytes,0.U)
    state:=Mux(cmd.paletteBytes+&cmd.transparencyBytes===0.U,setup,metadata)
    when(!valid){status:=1.U;state:=finish}
  }
  when(io.cmd.fire){
    cmd:=io.cmd.bits
    status:=0.U
    // commandCheck starts a zero-length generation for an illegal command.
    // No external command-valid fanout reaches the input datapath registers.
    state:=commandDecode
  }
}
