// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: QualifiedCodec.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import chisel3._
import chisel3.util._

/** Tool-only opaque boundary. The independent product is compiled as original
  * SV by Verilator, not elaborated or optimized inside the testbench firtool.
  * No clocked state, algorithm, data substitution or software callback here.
  */
class ExternalPngEncoder extends BlackBox {
  override def desiredName = "PngEncoder"
  val io=IO(new Bundle {
    val clock=Input(Clock());val reset=Input(Bool())
    val io_cmd=Flipped(Decoupled(new PngCommand))
    val io_metadata=Flipped(Decoupled(new StreamBeat))
    val io_in=Flipped(Decoupled(new StreamBeat));val io_out=Decoupled(new StreamBeat)
    val io_done=Decoupled(new PngCompletion)
  })
}
class ExternalPngDecoder extends BlackBox {
  override def desiredName = "PngDecoder"
  val io=IO(new Bundle {
    val clock=Input(Clock());val reset=Input(Bool())
    val io_cmd=Flipped(Decoupled(new PngCommand))
    val io_in=Flipped(Decoupled(new StreamBeat));val io_out=Decoupled(new StreamBeat)
    val io_descriptor=Decoupled(new PngDescriptor);val io_metadata=Decoupled(new StreamBeat)
    val io_done=Decoupled(new PngCompletion)
  })
}
class EncoderQualification extends Module {
  val io=IO(new Bundle {
    val cmd=Flipped(Decoupled(new PngCommand));val metadata=Flipped(Decoupled(new StreamBeat))
    val in=Flipped(Decoupled(new StreamBeat));val out=Decoupled(new StreamBeat)
    val done=Decoupled(new PngCompletion)
  })
  val external=Module(new ExternalPngEncoder)
  external.io.clock:=clock;external.io.reset:=reset.asBool
  external.io.io_cmd<>io.cmd;external.io.io_metadata<>io.metadata
  external.io.io_in<>io.in;io.out<>external.io.io_out;io.done<>external.io.io_done
}
class DecoderQualification extends Module {
  val io=IO(new Bundle {
    val cmd=Flipped(Decoupled(new PngCommand));val in=Flipped(Decoupled(new StreamBeat))
    val descriptor=Decoupled(new PngDescriptor);val metadata=Decoupled(new StreamBeat)
    val out=Decoupled(new StreamBeat);val done=Decoupled(new PngCompletion)
  })
  val external=Module(new ExternalPngDecoder)
  external.io.clock:=clock;external.io.reset:=reset.asBool
  external.io.io_cmd<>io.cmd;external.io.io_in<>io.in;io.out<>external.io.io_out
  io.descriptor<>external.io.io_descriptor;io.metadata<>external.io.io_metadata;io.done<>external.io.io_done
}
// References to actual hardware channels; these Scala records add no hardware.
case class EncoderChannels(cmd:DecoupledIO[PngCommand],metadata:DecoupledIO[StreamBeat],
  in:DecoupledIO[StreamBeat],out:DecoupledIO[StreamBeat],done:DecoupledIO[PngCompletion])
case class DecoderChannels(cmd:DecoupledIO[PngCommand],in:DecoupledIO[StreamBeat],
  descriptor:DecoupledIO[PngDescriptor],metadata:DecoupledIO[StreamBeat],
  out:DecoupledIO[StreamBeat],done:DecoupledIO[PngCompletion])
case class EncoderReference(io:EncoderChannels)
case class DecoderReference(io:DecoderChannels)
object QualifiedCodec {
  private def enabled = sys.env.get("OPENIMAGE_PNG_EXTERNAL").exists(_.nonEmpty)
  def encoder():EncoderReference = {
    if(enabled){val d=Module(new EncoderQualification)
      EncoderReference(EncoderChannels(d.io.cmd,d.io.metadata,d.io.in,d.io.out,d.io.done))}
    else{val d=Module(new PngEncoder)
      EncoderReference(EncoderChannels(d.io.cmd,d.io.metadata,d.io.in,d.io.out,d.io.done))}
  }
  def decoder():DecoderReference = {
    if(enabled){val d=Module(new DecoderQualification)
      DecoderReference(DecoderChannels(d.io.cmd,d.io.in,d.io.descriptor,d.io.metadata,d.io.out,d.io.done))}
    else{val d=Module(new PngDecoder)
      DecoderReference(DecoderChannels(d.io.cmd,d.io.in,d.io.descriptor,d.io.metadata,d.io.out,d.io.done))}
  }
}
