package memctrl

import chisel3._
import chisel3.util._

/** Updated top-level memory system I/O using the new names. */
class MemorySystemIO extends Bundle {
  val in  = Flipped(Decoupled(new ControllerRequest))
  val out = Decoupled(new ControllerResponse)
}

case class MemoryConfigurationParams(
  numberOfRanks:      Int = 8,
  numberOfBankGroups: Int = 8,
  numberOfBanks:      Int = 8
)

case class SingleChannelMemoryConfigurationParams(
  memConfiguration: MemoryConfigurationParams = MemoryConfigurationParams(),
  bankConfiguration: DRAMBankParams = DRAMBankParams(),
  trackPerformance: Boolean = true
)

class SingleChannelSystem(
  params: SingleChannelMemoryConfigurationParams = SingleChannelMemoryConfigurationParams()
) extends Module {
  val io = IO(new MemorySystemIO())

  val channel = Module(new Channel(params.memConfiguration, params.bankConfiguration))
  val memory_controller = Module(new MultiRankMemoryController(params.memConfiguration, params.bankConfiguration))

  // Connect the controller's memory command output to the channel's command input.
  channel.io.memCmd <> memory_controller.io.memCmd

  // Connect the channel's physical memory response back to the controller.
  memory_controller.io.phyResp <> channel.io.phyResp

  // Connect the user interface to the memory controller.
  memory_controller.io.in  <> io.in
  io.out                  <> memory_controller.io.out

  // Assume io.in and io.out are Decoupled interfaces.
  val inputFire  = io.in.valid  && io.in.ready
  val outputFire = io.out.valid && io.out.ready

  // If performance tracking is enabled:
  if (params.trackPerformance) {
    val perfStats = Module(new PerformanceStatistics)
    // Connect the performance monitor to the tap signals.
    perfStats.io.in_fire  := inputFire
    perfStats.io.in_bits  := io.in.bits
    perfStats.io.out_fire := outputFire
    perfStats.io.out_bits := io.out.bits
  }
}
