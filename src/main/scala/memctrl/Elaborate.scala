package memctrl

import circt.stage.ChiselStage
import io.circe._, io.circe.generic.auto._, io.circe.parser._

import java.nio.file.{Files, Paths}

case class Config(queueSize: Int, bankSchedulerPolicy: String, numChannels: Int, numRanks: Int, numBanks: Int)

object Elaborate extends App {
  // Load queueSize from a JSON file (e.g., "config.json")
  val jsonPath     = "src/main/config/config.json"
  val jsonString   = new String(Files.readAllBytes(Paths.get(jsonPath)))
  val parsedConfig = decode[Config](jsonString) match {
    case Right(config) => config
    case Left(error)   =>
      throw new RuntimeException(s"Failed to parse config.json: $error")
  }

  val queueSize           = parsedConfig.queueSize
  val bankSchedulerPolicy = parsedConfig.bankSchedulerPolicy
  val numChannels         = parsedConfig.numChannels
  val numRanks            = parsedConfig.numRanks
  val numBanks            = parsedConfig.numBanks

  printf("Found config value of queueSize = %d\n", queueSize)
  printf("Found bank scheduler policy value of policy = %s\n", bankSchedulerPolicy)

  // Instantiate memory configuration parameters with default values, then update
  val defaultMemoryConfig =
    MemoryConfigurationParameters(numberOfChannels = numChannels, numberOfRanks = numRanks, numberOfBanks = numBanks)
  val updatedMemoryConfig = defaultMemoryConfig

  // Instantiate
  val defaultControllerConfig = MemoryControllerParameters()
  val updatedControllerConfig =
    defaultControllerConfig.copy(queueSize = queueSize, openPagePolicy = (bankSchedulerPolicy == "OPEN_PAGE"))

  // FIRRTL -> SystemVerilog lowering options
  val firtoolOptions = Array(
    "--lowering-options=" + List(
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket"
    ).mkString(",")
  )

  val defaultLocalConfig = LocalConfigurationParameters(0, -1, -1)

  // Emit SystemVerilog
  ChiselStage.emitSystemVerilogFile(
    new MultiChannelSystem(
      SingleChannelMemoryConfigurationParams(
        memConfiguration = updatedMemoryConfig,
        controllerConfiguration = updatedControllerConfig
      ),
      localConfig = defaultLocalConfig
    ),
    args,
    firtoolOptions
  )
}
