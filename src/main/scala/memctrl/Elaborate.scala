package memctrl

import circt.stage.ChiselStage

object Elaborate extends App {
  // Configuration options for FIRRTL-to-SystemVerilog lowering.
  // These options disable local variables and packed arrays, and
  // use a specific format for source location annotations.
  val firtoolOptions = Array(
    "--lowering-options=" + List(
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket"
    ).mkString(",")
  )

  // Generate SystemVerilog file from the Chisel design.
  // This will elaborate MemoryTestSystem and place the generated files
  // in the specified target directory.
  ChiselStage.emitSystemVerilogFile(new SingleChannelSystem(), args, firtoolOptions)
}
