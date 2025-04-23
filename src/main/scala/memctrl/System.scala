package memctrl

import chisel3._
import chisel3.util._

/** Updated top-level memory system I/O using the new names. */
class MemorySystemIO(numberOfRanks: Int) extends Bundle {
  val in  = Flipped(Decoupled(new ControllerRequest))
  val out = Decoupled(new ControllerResponse)

  // Internals-Monitoring Signals
  val rankState = Output(Vec(numberOfRanks, UInt(3.W)))
  val reqQueueCount = Output(UInt(4.W))
  val respQueueCount = Output(UInt(4.W))
  val fsmReqQueueCounts = Output(Vec(numberOfRanks, UInt(3.W)))

}


case class SingleChannelMemoryConfigurationParams(
  memConfiguration: MemoryConfigurationParameters = MemoryConfigurationParameters(),
  bankConfiguration: DRAMBankParameters = DRAMBankParameters(),
  trackPerformance: Boolean = true
)

class SingleChannelSystem(
  params: SingleChannelMemoryConfigurationParams
) extends Module {
  val io = IO(new MemorySystemIO(params.memConfiguration.numberOfRanks))

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

  io.rankState := memory_controller.io.rankState

  // Connect internal queue counts
  io.reqQueueCount := memory_controller.io.reqQueueCount
  io.respQueueCount := memory_controller.io.respQueueCount
  io.fsmReqQueueCounts := memory_controller.io.fsmReqQueueCounts
}
