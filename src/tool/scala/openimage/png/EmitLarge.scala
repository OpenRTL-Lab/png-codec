// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: EmitLarge.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage
import java.nio.file.{Files,Paths}

/** 仅验证层的有界产物收集器：只消费Chisel已经握手的输出，不驱动或检查产品。 */
class LargeCapture(path:String) extends BlackBox with HasBlackBoxInline {
  val io=IO(new Bundle{val clock=Input(Clock());val valid=Input(Bool());val data=Input(UInt(64.W));val keep=Input(UInt(8.W));val complete=Input(Bool())})
  private val escaped=path.replace("\\","\\\\").replace("\"","\\\"")
  setInline("LargeCapture.sv",s"""module LargeCapture(input clock,valid,input [63:0] data,input [7:0] keep,input complete);
reg [7:0] bytes[0:1048575];
integer used=0; integer fd; integer i;
always @(posedge clock) begin
  if (valid) begin
    for (i=0;i<8;i=i+1) if (keep[i]) begin
      if (used>=1048576) $$fatal(1,"LARGE_CAPTURE_CAPACITY");
      bytes[used]=data[8*i+:8]; used=used+1;
    end
  end
  if (complete) begin
    fd=$$fopen("$escaped","wb");
    if (fd==0) $$fatal(1,"LARGE_CAPTURE_OPEN");
    for (i=0;i<used;i=i+1) $$fwrite(fd,"%c",bytes[i]);
    $$fclose(fd);
  end
end
endmodule
""")
}
class LargeSuite(w:Int,h:Int,direction:String,png:Seq[Int],pngHex:String,capture:String,negative:Boolean) extends Module {
  val io=IO(new Bundle{val finished=Output(Bool())})
  val rawBytes=((w+7)/8)*h
  val active=RegInit(false.B);val finished=RegInit(false.B)
  val ip=RegInit(0.U(32.W));val op=RegInit(0.U(32.W));val last=RegInit(false.B)
  val cycles=RegInit(0.U(32.W));cycles:=cycles+1.U;assert(cycles<500000000.U,"PNG_LARGE_WATCHDOG")
  io.finished:=finished
  if(direction=="encode"){
    val dut=QualifiedCodec.encoder();val sink=Module(new LargeCapture(capture));sink.io.clock:=clock
    dut.io.cmd.valid:= !active && !finished;dut.io.cmd.bits:=0.U.asTypeOf(new PngCommand)
    dut.io.cmd.bits.tag:="h81920001".U;dut.io.cmd.bits.imageWidth:=w.U;dut.io.cmd.bits.imageHeight:=h.U
    dut.io.cmd.bits.colorType:=0.U;dut.io.cmd.bits.bitDepth:=1.U;dut.io.cmd.bits.fixed:=true.B
    dut.io.cmd.bits.inputBytes:=rawBytes.U;dut.io.cmd.bits.outputLimit:="h40000000".U
    when(dut.io.cmd.fire){active:=true.B}
    val n=Mux(rawBytes.U-ip>=8.U,8.U,rawBytes.U-ip)(3,0)
    dut.io.in.valid:=active && ip<rawBytes.U;dut.io.in.bits.data:=Mux(negative.B && ip===0.U,128.U,0.U)
    dut.io.in.bits.keep:=((1.U(9.W)<<n)-1.U)(7,0);dut.io.in.bits.last:=ip+n===rawBytes.U
    dut.io.metadata.valid:=false.B;dut.io.metadata.bits:=0.U.asTypeOf(new StreamBeat)
    when(dut.io.in.fire){ip:=ip+n}
    dut.io.out.ready:=true.B;dut.io.done.ready:=true.B
    sink.io.valid:=dut.io.out.fire;sink.io.data:=dut.io.out.bits.data;sink.io.keep:=dut.io.out.bits.keep;sink.io.complete:=dut.io.done.fire
    when(dut.io.out.fire){
      val k=PopCount(dut.io.out.bits.keep)
      assert(!last && k=/=0.U && dut.io.out.bits.keep===((1.U(9.W)<<k)-1.U)(7,0),"PNG_LARGE_ENCODER_KEEP")
      op:=op+k;when(dut.io.out.bits.last){last:=true.B}
    }
    when(dut.io.done.fire){
      val d=dut.io.done.bits
      assert(d.status===0.U && d.tag==="h81920001".U && d.imageWidth===w.U && d.imageHeight===h.U && d.colorType===0.U && d.bitDepth===1.U && d.pixels===(w*h).U,"PNG_LARGE_ENCODER_COMPLETION")
      assert(ip===rawBytes.U && d.inputBytes===ip && last && d.outputBytes===op,"PNG_LARGE_ENCODER_COUNTS")
      finished:=true.B;active:=false.B;printf(p"PNG_LARGE_ENCODER_PASS pixels=${d.pixels} input=${ip} output=${op} cycles=${cycles}\n")
    }
  }else{
    val dut=QualifiedCodec.decoder();val source=Mem(png.size,UInt(8.W));loadMemoryFromFileInline(source,pngHex)
    val described=RegInit(false.B)
    dut.io.cmd.valid:= !active && !finished;dut.io.cmd.bits:=0.U.asTypeOf(new PngCommand)
    dut.io.cmd.bits.tag:="h81920002".U;dut.io.cmd.bits.inputBytes:=png.size.U;dut.io.cmd.bits.outputLimit:=rawBytes.U
    when(dut.io.cmd.fire){active:=true.B}
    val n=Mux(png.size.U-ip>=8.U,8.U,png.size.U-ip)(3,0);val bytes=Wire(Vec(8,UInt(8.W)))
    for(i<-0 until 8){bytes(i):=Mux(i.U<n,source((ip+i.U)(math.max(1,log2Ceil(png.size))-1,0)),0.U)}
    dut.io.in.valid:=active && ip<png.size.U;dut.io.in.bits.data:=bytes.asUInt;dut.io.in.bits.keep:=((1.U(9.W)<<n)-1.U)(7,0);dut.io.in.bits.last:=ip+n===png.size.U
    when(dut.io.in.fire){ip:=ip+n}
    dut.io.descriptor.ready:=true.B;dut.io.metadata.ready:=true.B;dut.io.out.ready:=true.B;dut.io.done.ready:=true.B
    when(dut.io.descriptor.fire){
      val d=dut.io.descriptor.bits
      assert(!described && d.tag==="h81920002".U && d.imageWidth===w.U && d.imageHeight===h.U && d.colorType===0.U && d.bitDepth===1.U && d.paletteBytes===0.U && d.transparencyBytes===0.U,"PNG_LARGE_DESCRIPTOR")
      described:=true.B
    }
    assert(!dut.io.metadata.valid,"PNG_LARGE_UNEXPECTED_METADATA")
    when(dut.io.out.fire){
      val k=PopCount(dut.io.out.bits.keep)
      assert(described && !last && k=/=0.U && op+k<=rawBytes.U && dut.io.out.bits.keep===((1.U(9.W)<<k)-1.U)(7,0),"PNG_LARGE_DECODER_LENGTH")
      for(i<-0 until 8){when(dut.io.out.bits.keep(i)){
        val gold=Mux(negative.B && op===0.U && i.U===0.U,1.U,0.U)
        assert(dut.io.out.bits.data(8*i+7,8*i)===gold,"PNG_LARGE_BYTE_MISMATCH")
      }}
      assert(dut.io.out.bits.last===(op+k===rawBytes.U),"PNG_LARGE_LAST")
      op:=op+k;when(dut.io.out.bits.last){last:=true.B}
    }
    when(dut.io.done.fire){
      val d=dut.io.done.bits
      assert(d.status===0.U && d.tag==="h81920002".U && d.pixels===(w*h).U && d.imageWidth===w.U && d.imageHeight===h.U,"PNG_LARGE_DECODER_COMPLETION")
      assert(ip===png.size.U && d.inputBytes===ip && last && op===rawBytes.U && d.outputBytes===op,"PNG_LARGE_DECODER_COUNTS")
      finished:=true.B;active:=false.B;printf(p"PNG_LARGE_DECODER_PASS pixels=${d.pixels} input=${ip} output=${op} cycles=${cycles}\n")
    }
  }
}
object EmitLarge extends App {
  require(args.length==7);val out=Paths.get(args(0));require(!Files.exists(out));Files.createDirectories(out)
  val input=Paths.get(args(4)).toAbsolutePath;val png=Files.readAllBytes(input).map(_&255).toSeq
  val hex=input.resolveSibling("software.hex");Files.writeString(hex,png.map(x=>f"$x%02x\n").mkString)
  ChiselStage.emitSystemVerilog(new LargeSuite(args(2).toInt,args(3).toInt,args(1),png,hex.toString,Paths.get(args(5)).toAbsolutePath.toString,args(6)=="negative"),
    firtoolOpts=Array("-O=release","-disable-all-randomization","-split-verilog",s"-o=$out"))
}
