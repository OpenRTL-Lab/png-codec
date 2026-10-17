// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: EmitProduct.scala
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
// SPDX-License-Identifier: MIT
package openimage.png
import _root_.circt.stage.ChiselStage
import java.nio.file.{Files,Paths}

/** 无测试顶层、参考ROM或软件回调的独立可综合产品。 */
object EmitProduct extends App {
  require(args.length==2,"direction out")
  val out=Paths.get(args(1));require(!Files.exists(out));Files.createDirectories(out)
  val options=Array("-O=release","-disable-all-randomization","-strip-debug-info","-split-verilog",s"-o=$out")
  args(0) match {
    case "encode" => ChiselStage.emitSystemVerilog(new PngEncoder,firtoolOpts=options)
    case "decode" => ChiselStage.emitSystemVerilog(new PngDecoder,firtoolOpts=options)
    case _ => throw new IllegalArgumentException("encode or decode")
  }
}
