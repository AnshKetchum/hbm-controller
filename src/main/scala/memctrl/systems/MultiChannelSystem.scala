package memctrl

import chisel3._
import chisel3.util._

/**
 * Multi-channel memory system: same top-level IO as SingleChannelSystem,
 * but instantiates multiple channel+controller pairs and routes
 * requests based on decoded channel index.
 */
class MultiChannelSystem(
  params: SingleChannelMemoryConfigurationParams,
  localConfig: LocalConfigurationParameters
) extends Module {
  val io = IO(new MemorySystemIO(params.memConfiguration))

  // Address decoder to extract channel index from request address
  val decoder = Module(new AddressDecoder(params.memConfiguration))
  decoder.io.addr := io.in.bits.addr

  // Shared request ID counter
  val requestId = RegInit(0.U(32.W))
  val inputFire = io.in.valid && io.in.ready
  when(inputFire) { requestId := requestId + 1.U }

  // Instantiate per-channel channel and controller
  val channels = Seq.tabulate(params.memConfiguration.numberOfChannels) { i =>
    val ch = Module(new Channel(
      params.memConfiguration,
      params.bankConfiguration,
      localConfig.copy(channelIndex = i),
      params.trackPerformance,
      params.memConfiguration.memoryQueueSize
    ))
    ch
  }
  val controllers = Seq.tabulate(params.memConfiguration.numberOfChannels) { i =>
    val mc = Module(new MultiRankMemoryController(
      params.memConfiguration,
      params.bankConfiguration,
      params.controllerConfiguration,
      localConfig.copy(channelIndex = i),
      params.trackPerformance
    ))
    mc
  }

  // Wire each channel to its controller
  for (i <- 0 until params.memConfiguration.numberOfChannels) {
    channels(i).io.memCmd <> controllers(i).io.memCmd
    controllers(i).io.phyResp <> channels(i).io.phyResp
  }

  // Build per-channel ControllerRequest wires
  val ctrlReqVec = Seq.tabulate(params.memConfiguration.numberOfChannels) { i =>
    val w = Wire(Decoupled(new ControllerRequest))
    // valid only for the decoded channel
    w.valid := io.in.valid && (decoder.io.channelIndex === i.U)
    // map SystemRequest fields
    w.bits.rd_en      := io.in.bits.rd_en
    w.bits.wr_en      := io.in.bits.wr_en
    w.bits.addr       := io.in.bits.addr
    w.bits.wdata      := io.in.bits.wdata
    w.bits.request_id := requestId
    w
  }

  // Connect to controllers and drive top-level ready
  io.in.ready := false.B
  for (i <- 0 until params.memConfiguration.numberOfChannels) {
    controllers(i).io.in <> ctrlReqVec(i)
    // only the selected channel's ready enables top-level ready
    when(decoder.io.channelIndex === i.U) {
      io.in.ready := ctrlReqVec(i).ready
    }
  }

  // Arbiter to collect responses
  val respArb = Module(new Arbiter(new ControllerResponse, params.memConfiguration.numberOfChannels))
  for (i <- 0 until params.memConfiguration.numberOfChannels) {
    respArb.io.in(i) <> controllers(i).io.out
  }
  io.out <> respArb.io.out

  // Performance statistics per-channel (optional)
  if (params.trackPerformance) {
    for (i <- 0 until params.memConfiguration.numberOfChannels) {
      val perfStats = Module(new SystemQueuePerformanceStatistics)
      val inFireCh = io.in.valid && io.in.ready && (decoder.io.channelIndex === i.U)
      val outFire  = io.out.valid && io.out.ready
      perfStats.io.in_fire  := inFireCh
      perfStats.io.in_bits  := ctrlReqVec(i).bits
      perfStats.io.out_fire := outFire
      perfStats.io.out_bits := io.out.bits
    }
  }

  // Expose internal signals (from channel 0, could be extended)
  io.rankState         := controllers(0).io.rankState
  io.reqQueueCount     := controllers(0).io.reqQueueCount
  io.respQueueCount    := controllers(0).io.respQueueCount
  io.fsmReqQueueCounts := controllers(0).io.fsmReqQueueCounts
  io.activeRanks       := controllers(0).io.rankState.count(_ =/= 0.U)
}