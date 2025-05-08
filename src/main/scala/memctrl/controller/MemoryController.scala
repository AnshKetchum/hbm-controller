package memctrl

import chisel3._
import chisel3.util._

/**
 * Memory controller with one FSM per physical bank (across ranks, bank groups, and banks)
 */
class MultiRankMemoryController(
  params: MemoryConfigurationParameters,
  bankParams: DRAMBankParameters,
  trackPerformance: Boolean = false,
  channelIndex: Int = 0,
) extends Module {
  val io = IO(new Bundle {
    val in      = Flipped(Decoupled(new ControllerRequest))
    val out     = Decoupled(new ControllerResponse)
    val memCmd  = Decoupled(new PhysicalMemoryCommand)
    val phyResp = Flipped(Decoupled(new PhysicalMemoryResponse))

    // Monitoring
    val rankState         = Output(Vec(params.numberOfRanks, UInt(3.W)))
    val reqQueueCount     = Output(UInt(4.W))
    val respQueueCount    = Output(UInt(4.W))
    val fsmReqQueueCounts  = Output(Vec(params.numberOfRanks * params.numberOfBankGroups * params.numberOfBanks, UInt(3.W)))
  })

  // FIFO for incoming requests and outgoing responses
  val reqQueue  = Module(new Queue(new ControllerRequest, entries = 128))
  val respQueue = Module(new Queue(new ControllerResponse, entries = 128))
  reqQueue.io.enq <> io.in
  io.out          <> respQueue.io.deq

  // Physical command queue
  val cmdQueue = Module(new Queue(new PhysicalMemoryCommand, entries = 2048))
  io.memCmd        <> cmdQueue.io.deq

  // total number of banks across all ranks and groups
  val banksPerRank    = params.numberOfBankGroups * params.numberOfBanks
  val totalBankFSMs   = params.numberOfRanks * banksPerRank

  // instantiate one FSM per bank, now propagating trackPerformance and LocalConfigurationParameters
  val fsmVec = VecInit(Seq.tabulate(totalBankFSMs) { i =>
    // compute rank/group/bank from flat index
    val r = i / banksPerRank
    val rem = i % banksPerRank
    val g = rem / params.numberOfBanks
    val b = rem % params.numberOfBanks

    // create a localConfiguration instance
    val locCfg = LocalConfigurationParameters(
      channelIndex = channelIndex,
      rankIndex    = r,
      bankGroupIndex = g,
      bankIndex    = b
    )

    // pass both bankParams and locCfg plus the trackPerformance flag
    Module(new MemoryControllerFSM(bankParams, locCfg, params, trackPerformance)).io
  })


  // create per-FSM request queues
  val fsmReqQueues = VecInit(Seq.fill(totalBankFSMs) {
    Module(new Queue(new ControllerRequest, entries = 2048)).io
  })

  // decode bank index from address
  val rankBits       = log2Ceil(params.numberOfRanks)
  val groupBits      = log2Ceil(params.numberOfBankGroups)
  val bankBits       = log2Ceil(params.numberOfBanks)
  val groupShift     = bankBits
  val rankShift      = bankBits + groupBits
  def extractIndices(addr: UInt): (UInt, UInt, UInt) = {
    val bankIdx  = addr(bankBits-1, 0)
    val groupIdx = addr(groupShift + groupBits -1, groupShift)
    val rankIdx  = addr(rankShift + rankBits -1, rankShift)
    (rankIdx, groupIdx, bankIdx)
  }

  // demux: drive all enq.valid to false initially
  val enqReadyVec = Wire(Vec(totalBankFSMs, Bool()))
  for(i <- 0 until totalBankFSMs) {
    fsmReqQueues(i).enq.valid := false.B
    fsmReqQueues(i).enq.bits  := 0.U.asTypeOf(new ControllerRequest)
    enqReadyVec(i) := false.B
  }

  when(reqQueue.io.deq.valid) {
    val (r, g, b) = extractIndices(reqQueue.io.deq.bits.addr)
    val flatIdx   = r * banksPerRank.U + g * params.numberOfBanks.U + b
    for(i <- 0 until totalBankFSMs) {
      when(flatIdx === i.U) {
        fsmReqQueues(i).enq.valid := true.B
        fsmReqQueues(i).enq.bits  := reqQueue.io.deq.bits
        enqReadyVec(i) := fsmReqQueues(i).enq.ready
      }
    }
  }
  reqQueue.io.deq.ready := enqReadyVec.asUInt.orR

  // connect FSM req ports
  for(i <- 0 until totalBankFSMs) {
    fsmVec(i).req <> fsmReqQueues(i).deq
  }

  // round-robin arbiter for cmd outputs from all FSMs
  val cmdArb = Module(new RRArbiter(new PhysicalMemoryCommand, totalBankFSMs))
  for(i <- 0 until totalBankFSMs) {
    cmdArb.io.in(i) <> fsmVec(i).cmdOut
  }
  cmdQueue.io.enq <> cmdArb.io.out

  // address decoder for responses
  val respDecoder = Module(new AddressDecoder(params))
  respDecoder.io.addr := io.phyResp.bits.addr
  val respRank  = respDecoder.io.rankIndex
  val respGroup = respDecoder.io.bankGroupIndex
  val respBank  = respDecoder.io.bankIndex
  val respFlat  = respRank * banksPerRank.U + respGroup * params.numberOfBanks.U + respBank

  // route phyResp to target FSM
  for(i <- 0 until totalBankFSMs) {
    val isTarget = (respFlat === i.U)
    fsmVec(i).phyResp.valid := io.phyResp.valid && isTarget
    fsmVec(i).phyResp.bits  := io.phyResp.bits
    fsmVec(i).phyResp.ready := true.B
  }
  io.phyResp.ready := fsmVec(respFlat).phyResp.ready

  // collect responses from FSMs via arbiter
  val respArb = Module(new RRArbiter(new ControllerResponse, totalBankFSMs))
  for(i <- 0 until totalBankFSMs) {
    respArb.io.in(i) <> fsmVec(i).resp
  }
  respQueue.io.enq.valid := respArb.io.out.valid
  respQueue.io.enq.bits  := respArb.io.out.bits
  respArb.io.out.ready   := respQueue.io.enq.ready

  // Enable Performance Tracking for the command queue
  if (trackPerformance) {
    val requestQueueTracker = Module(new CommandQueuePerformanceStatistics)
    requestQueueTracker.io.in_fire := cmdQueue.io.enq.fire
    requestQueueTracker.io.in_bits := cmdQueue.io.enq.bits
    requestQueueTracker.io.out_fire := io.phyResp.fire
    requestQueueTracker.io.out_bits := io.phyResp.bits
  }


  // monitoring outputs
  io.reqQueueCount := reqQueue.io.count
  io.respQueueCount := respQueue.io.count
  for(i <- 0 until totalBankFSMs) {
    io.fsmReqQueueCounts(i) := fsmReqQueues(i).count
  }

  // aggregate rankState: for backward compatibility, show max state per rank
  for(r <- 0 until params.numberOfRanks) {
    val slice = fsmVec.slice(r*banksPerRank, (r+1)*banksPerRank)
    io.rankState(r) := slice.map(_.stateOut).reduce(_ max _)
  }
}