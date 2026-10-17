// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: EmitPerformance.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import _root_.circt.stage.ChiselStage
import java.nio.file.{Files,Paths}
import scala.jdk.CollectionConverters._

/** 测试层只捕获每配置冷启动首份完整PNG，RAM内收集后一次写文件。 */
class PerformanceCapture(directory:String,capacity:Int) extends BlackBox with HasBlackBoxInline {
  val io=IO(new Bundle{
    val clock=Input(Clock());val valid=Input(Bool());val data=Input(UInt(64.W));val keep=Input(UInt(8.W))
    val complete=Input(Bool());val caseId=Input(UInt(32.W))
  })
  private val path=directory.replace("\\","\\\\").replace("\"","\\\"")
  setInline("PerformanceCapture.sv",s"""module PerformanceCapture(input clock,valid,input [63:0] data,input [7:0] keep,input complete,input [31:0] caseId);
reg [7:0] bytes[0:${capacity-1}];
integer used=0; integer i; integer fd;
always @(posedge clock) begin
  if(valid) for(i=0;i<8;i=i+1) if(keep[i]) begin
    if(used>=$capacity) $$fatal(1,"PNG_PERF_CAPTURE_CAPACITY");
    bytes[used]=data[8*i+:8];used=used+1;
  end
  if(complete) begin
    fd=$$fopen($$sformatf("$path/observed-%02d.png",caseId),"wb");
    if(fd==0) $$fatal(1,"PNG_PERF_CAPTURE_OPEN");
    for(i=0;i<used;i=i+1) $$fwrite(fd,"%c",bytes[i]);
    $$fclose(fd);used=0;
  end
end
endmodule
""")
}

class PerformanceSuite(cases:Seq[Seq[Int]],direction:String,vectors:String,capture:String) extends Module {
  val io=IO(new Bundle{val finished=Output(Bool());val negative=Input(Bool())})
  // W H type depth filter mode rawN rawOff plen tlen metaOff pngN pngOff batchN
  val ci=RegInit(0.U(math.max(1,log2Ceil(cases.size+1)).W));val task=RegInit(0.U(6.W))
  val table=cases.transpose.map(v=>VecInit(v.map(_.U(32.W))))
  def f(n:Int):UInt=if(cases.size==1)table(n)(0) else table(n)(ci(math.max(1,log2Ceil(cases.size))-1,0))
  val rawN=math.max(2,cases.map(c=>c(6)+c(7)).max)
  val pngN=math.max(2,cases.map(c=>c(11)+c(12)).max)
  val metaN=math.max(2,cases.map(c=>c(8)+c(9)+c(10)).max)
  val raw=Mem(rawN,UInt(8.W));val png=Mem(pngN,UInt(8.W));val meta=Mem(metaN,UInt(8.W))
  loadMemoryFromFileInline(raw,s"$vectors/raw.hex");loadMemoryFromFileInline(png,s"$vectors/png.hex");loadMemoryFromFileInline(meta,s"$vectors/meta.hex")
  def addr(v:UInt,n:Int)=v(math.max(1,log2Ceil(n))-1,0)
  val capacity=cases.map(_(6)).max*2+65536
  val golden=if(direction=="encode")Some(Mem(capacity,UInt(8.W))) else None
  val firstSize=RegInit(0.U(32.W))
  val phase=RegInit(0.U(2.W));val warm=RegInit(0.U(3.W));val dutReset=reset.asBool || phase===0.U
  val cycles=RegInit(0.U(64.W));cycles:=cycles+1.U
  assert(cycles<10000000000L.U,"PNG_PERF_WATCHDOG")
  io.finished:=phase===3.U
  when(phase===0.U){warm:=warm+1.U;when(warm===4.U){warm:=0.U;phase:=1.U}}
  val ip=RegInit(0.U(32.W));val op=RegInit(0.U(32.W));val mp=RegInit(0.U(32.W))
  val a=Reg(UInt(64.W));val u=Reg(UInt(64.W));val v=Reg(UInt(64.W));val p=Reg(UInt(64.W));val q=Reg(UInt(64.W))
  val mu=RegInit(0.U(64.W));val mv=RegInit(0.U(64.W));val desc=RegInit(0.U(64.W))
  val seenIn=RegInit(false.B);val seenOut=RegInit(false.B);val last=RegInit(false.B);val described=RegInit(false.B)
  val cmd=Wire(Decoupled(new PngCommand));val in=Wire(Decoupled(new StreamBeat))
  val out=Wire(Decoupled(new StreamBeat));val done=Wire(Decoupled(new PngCompletion))
  val tag="hce000000".U(32.W)+(ci<<6)+task
  cmd.valid:=phase===1.U;cmd.bits:=0.U.asTypeOf(new PngCommand)
  cmd.bits.tag:=tag;cmd.bits.inputBytes:= (if(direction=="encode")f(6) else f(11))
  cmd.bits.outputLimit:= (if(direction=="encode")capacity.U else f(6))
  cmd.bits.imageWidth:=f(0);cmd.bits.imageHeight:=f(1);cmd.bits.colorType:=f(2);cmd.bits.bitDepth:=f(3)
  cmd.bits.filter:=f(4);cmd.bits.fixed:=f(5)(0);cmd.bits.paletteBytes:=f(8);cmd.bits.transparencyBytes:=f(9)
  val ml=f(8)+f(9);val inN=cmd.bits.inputBytes
  val n=Mux(inN-ip>=8.U,8.U,inN-ip)(3,0);val inputData=Wire(Vec(8,UInt(8.W)))
  for(i<-0 until 8){inputData(i):=Mux(i.U<n,
    if(direction=="encode")raw(addr(f(7)+ip+i.U,rawN)) else png(addr(f(12)+ip+i.U,pngN)),0.U)}
  in.valid:=phase===2.U && ip<inN && (if(direction=="encode")mp===ml else true.B)
  in.bits.data:=inputData.asUInt;in.bits.keep:=((1.U(9.W)<<n)-1.U)(7,0);in.bits.last:=ip+n===inN
  out.ready:=true.B;done.ready:=true.B
  when(cmd.fire){
    assert(phase===1.U,"PNG_PERF_COMMAND_PHASE");phase:=2.U;a:=cycles
    ip:=0.U;op:=0.U;mp:=0.U;seenIn:=false.B;seenOut:=false.B;last:=false.B;described:=false.B;mu:=0.U;mv:=0.U;desc:=0.U
  }
  when(in.fire){when(!seenIn){u:=cycles;seenIn:=true.B};v:=cycles;ip:=ip+n}
  if(direction=="encode"){
    val dut=withReset(dutReset){QualifiedCodec.encoder()}
    dut.io.cmd<>cmd;dut.io.in<>in;out<>dut.io.out;done<>dut.io.done
    val k=Mux(ml-mp>=8.U,8.U,ml-mp)(3,0);val data=Wire(Vec(8,UInt(8.W)))
    for(i<-0 until 8){data(i):=Mux(i.U<k,meta(addr(f(10)+mp+i.U,metaN)),0.U)}
    dut.io.metadata.valid:=phase===2.U && mp<ml;dut.io.metadata.bits.data:=data.asUInt
    dut.io.metadata.bits.keep:=((1.U(9.W)<<k)-1.U)(7,0);dut.io.metadata.bits.last:=mp+k===ml
    when(dut.io.metadata.fire){when(mp===0.U){mu:=cycles};mv:=cycles;mp:=mp+k}
    val sink=Module(new PerformanceCapture(capture,capacity));sink.io.clock:=clock
    sink.io.valid:=out.fire && task===0.U;sink.io.data:=out.bits.data;sink.io.keep:=out.bits.keep
    sink.io.complete:=done.fire && task===0.U;sink.io.caseId:=ci
  }else{
    val dut=withReset(dutReset){QualifiedCodec.decoder()}
    dut.io.cmd<>cmd;dut.io.in<>in;out<>dut.io.out;done<>dut.io.done
    dut.io.descriptor.ready:=true.B;dut.io.metadata.ready:=true.B
    when(dut.io.descriptor.fire){
      val d=dut.io.descriptor.bits
      assert(!described && d.tag===tag && d.imageWidth===f(0) && d.imageHeight===f(1) && d.colorType===f(2) && d.bitDepth===f(3) && d.paletteBytes===f(8) && d.transparencyBytes===f(9),"PNG_PERF_DESCRIPTOR")
      described:=true.B;desc:=cycles
    }
    when(dut.io.metadata.fire){
      val k=PopCount(dut.io.metadata.bits.keep)
      assert(described && k=/=0.U && mp+k<=ml && dut.io.metadata.bits.keep===((1.U(9.W)<<k)-1.U)(7,0),"PNG_PERF_META_KEEP")
      for(i<-0 until 8){when(i.U<k){assert(dut.io.metadata.bits.data(8*i+7,8*i)===meta(addr(f(10)+mp+i.U,metaN)),"PNG_PERF_METADATA")}}
      assert(dut.io.metadata.bits.last===(mp+k===ml),"PNG_PERF_META_LAST")
      when(mp===0.U){mu:=cycles};mv:=cycles;mp:=mp+k
    }
  }
  when(out.fire){
    val k=PopCount(out.bits.keep)
    assert(phase===2.U && !last && k=/=0.U && out.bits.keep===((1.U(9.W)<<k)-1.U)(7,0),"PNG_PERF_OUTPUT_KEEP")
    when(!seenOut){p:=cycles;seenOut:=true.B};q:=cycles
    if(direction=="encode"){
      assert(op+k<=capacity.U,"PNG_PERF_OUTPUT_CAPACITY")
      // Only a bounded file prefix is timestamped, not the internal entropy
      // handshake. Offline RFC parsing maps its first image token back to the
      // actual external beat. Signature/header bytes do not count as image.
      val idatStart=41.U+ml+Mux(f(8)=/=0.U,12.U,0.U)+Mux(f(9)=/=0.U,12.U,0.U)
      when(op+k>idatStart && op<idatStart+10.U) {
        printf(p"PNG_PERF_PREFIX case=${ci} task=${task} offset=${op} bytes=${k} cycle=${cycles}\n")
      }
      for(i<-0 until 8){when(i.U<k){
        when(task===0.U){golden.get.write(addr(op+i.U,capacity),out.bits.data(8*i+7,8*i))}
        .otherwise{assert(out.bits.data(8*i+7,8*i)===golden.get(addr(op+i.U,capacity)),"PNG_PERF_REPEAT_BYTE")}
      }}
      when(task=/=0.U){assert(out.bits.last===(op+k===firstSize),"PNG_PERF_REPEAT_LAST")}
    }else{
      assert(described && mp===ml && op+k<=f(6),"PNG_PERF_OUTPUT_ORDER")
      for(i<-0 until 8){when(i.U<k){assert(out.bits.data(8*i+7,8*i)===raw(addr(f(7)+op+i.U,rawN)),"PNG_PERF_PIXEL")}}
      assert(out.bits.last===(op+k===f(6)),"PNG_PERF_OUTPUT_LAST")
    }
    op:=op+k;when(out.bits.last){last:=true.B}
  }
  when(done.fire){
    val d=done.bits
    assert(phase===2.U && d.status===0.U,"PNG_PERF_STATUS")
    assert(d.tag===(tag^Mux(io.negative,1.U,0.U)),"PNG_PERF_TAG_NEGATIVE")
    assert(seenIn && seenOut && last && ip===inN && d.inputBytes===ip && d.outputBytes===op && mp===ml,"PNG_PERF_COUNTS")
    assert(d.pixels===f(0)*f(1) && d.imageWidth===f(0) && d.imageHeight===f(1) && d.colorType===f(2) && d.bitDepth===f(3) && d.paletteBytes===f(8) && d.transparencyBytes===f(9),"PNG_PERF_COMPLETION")
    if(direction=="encode"){when(task===0.U){firstSize:=op}.otherwise{assert(op===firstSize,"PNG_PERF_REPEAT_SIZE")}}
    else{assert(op===f(6) && described,"PNG_PERF_RAW_SIZE")}
    printf(p"PNG_PERF_EVENT case=${ci} task=${task} tag=${tag} a=${a} u=${u} v=${v} p=${p} q=${q} c=${cycles} mu=${mu} mv=${mv} descriptor=${desc} input=${ip} output=${op} pixels=${d.pixels} metadata=${mp}\n")
    when(task===0.U && f(13)=/=0.U){task:=1.U;phase:=0.U}
    .elsewhen(task<f(13)){task:=task+1.U;phase:=1.U}
    .otherwise{
      task:=0.U;ci:=ci+1.U;phase:=0.U
      when(ci===(cases.size-1).U){phase:=3.U;printf(p"PNG_PERF_SUITE_PASS\n")}
    }
  }
}
object EmitPerformance extends App {
  require(args.length==4);val out=Paths.get(args(0));require(!Files.exists(out));Files.createDirectories(out)
  val v=Paths.get(args(2)).toAbsolutePath
  val cases=Files.readAllLines(v.resolve("cases.txt")).asScala.filter(_.nonEmpty).map(_.split(" ").map(_.toInt).toSeq).toSeq
  ChiselStage.emitSystemVerilog(new PerformanceSuite(cases,args(1),v.toString,Paths.get(args(3)).toAbsolutePath.toString),
    firtoolOpts=Array("-O=release","-disable-all-randomization","-split-verilog",s"-o=$out"))
}
