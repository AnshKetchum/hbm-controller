package memctrl

import circt.stage.ChiselStage
import io.circe._, io.circe.generic.auto._, io.circe.parser._

import java.nio.file.{Files, Paths}

case class Config(queueSize: Int)

object Elaborate extends App {
  // Load queueSize from a JSON file (e.g., "config.json")
  val jsonPath     = "src/main/config/config.json"
  val jsonString   = new String(Files.readAllBytes(Paths.get(jsonPath)))
  val parsedConfig = decode[Config](jsonString) match {
    case Right(config) => config
    case Left(error)   =>
      throw new RuntimeException(s"Failed to parse config.json: $error")
  }

  val queueSize = parsedConfig.queueSize
  printf("Found config value of queueSize = %d\n", queueSize)

  // Instantiate with default values
  val defaultConfig = MemoryConfigurationParameters()

  // Create a modified copy with the new controllerQueueSize
  val updatedConfig = defaultConfig.copy(controllerQueueSize = queueSize)

  // FIRRTL -> SystemVerilog lowering options
  val firtoolOptions = Array(
    "--lowering-options=" + List(
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket"
    ).mkString(",")
  )

  // Emit SystemVerilog
  ChiselStage.emitSystemVerilogFile(
    new SingleChannelSystem(SingleChannelMemoryConfigurationParams(memConfiguration = updatedConfig)),
    args,
    firtoolOptions
  )
}
