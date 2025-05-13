package memctrl

import chisel3._
import chisel3.util._

case class SingleChannelMemoryConfigurationParams(
  memConfiguration: MemoryConfigurationParameters = MemoryConfigurationParameters(),
  bankConfiguration: DRAMBankParameters = DRAMBankParameters(),
  trackPerformance: Boolean = true
)

/** Updated top-level memory system I/O using the new names. */
class MemorySystemIO(params: MemoryConfigurationParameters) extends Bundle {
  val in  = Flipped(Decoupled(new SystemRequest))
  val out = Decoupled(new ControllerResponse)

  // Internals-Monitoring Signals
  val rankState = Output(Vec(params.numberOfRanks, UInt(3.W)))
  val reqQueueCount = Output(UInt(4.W))
  val respQueueCount = Output(UInt(4.W))
  val fsmReqQueueCounts  = Output(Vec(params.numberOfRanks * params.numberOfBankGroups * params.numberOfBanks, UInt(3.W)))

  // New signal to expose active ranks count
  val activeRanks = Output(UInt(log2Ceil(params.numberOfRanks + 1).W))
}

class SingleChannelSystem(
  params: SingleChannelMemoryConfigurationParams
) extends Module {
  val io = IO(new MemorySystemIO(params.memConfiguration))

  val channel = Module(new Channel(params.memConfiguration, params.bankConfiguration, 0, params.trackPerformance, params.memConfiguration.memoryQueueSize))
  val memory_controller = Module(new MultiRankMemoryController(params.memConfiguration, params.bankConfiguration, params.trackPerformance, 0, params.memConfiguration.controllerQueueSize))

  // Connect the controller's memory command output to the channel's command input.
  channel.io.memCmd <> memory_controller.io.memCmd

  // Connect the channel's physical memory response back to the controller.
  memory_controller.io.phyResp <> channel.io.phyResp

  // Internal request ID register
  val requestId = RegInit(0.U(32.W))


  // Assume io.in and io.out are Decoupled interfaces.
  val inputFire  = io.in.valid  && io.in.ready
  val outputFire = io.out.valid && io.out.ready

  // Increment request ID on input fire
  when(inputFire) {
    requestId := requestId + 1.U
  }

  // Wire input to memory controller, appending request_id
  memory_controller.io.in.valid := io.in.valid
  io.in.ready := memory_controller.io.in.ready

  val ctrlReq = Wire(new ControllerRequest)
  ctrlReq.rd_en := io.in.bits.rd_en
  ctrlReq.wr_en := io.in.bits.wr_en
  ctrlReq.addr  := io.in.bits.addr
  ctrlReq.wdata := io.in.bits.wdata
  ctrlReq.request_id := requestId

  memory_controller.io.in.bits := ctrlReq

  // Connect the user interface to the memory controller.
  io.out                  <> memory_controller.io.out

  // If performance tracking is enabled:
  if (params.trackPerformance) {
    val perfStats = Module(new SystemQueuePerformanceStatistics)
    // Connect the performance monitor to the tap signals.
    perfStats.io.in_fire  := inputFire
    perfStats.io.in_bits  := ctrlReq
    perfStats.io.out_fire := outputFire
    perfStats.io.out_bits := io.out.bits
  }

  io.rankState := memory_controller.io.rankState

  // Connect internal queue counts
  io.reqQueueCount := memory_controller.io.reqQueueCount
  io.respQueueCount := memory_controller.io.respQueueCount
  io.fsmReqQueueCounts := memory_controller.io.fsmReqQueueCounts

  // Calculate the number of active ranks
  val activeRanksCount = memory_controller.io.rankState.count(_ =/= 0.U)

  // Expose the number of active ranks to the top-level I/O
  io.activeRanks := activeRanksCount
}
