package memctrl

import chisel3._
import chisel3.util._

class MultiRankMemoryController(
  params: MemoryConfigurationParameters,
  bankParams: DRAMBankParameters,
  trackPerformance: Boolean = false,
  channelIndex: Int = 0,
  queueSize: Int = 2,
  memoryCommandQueueSize: Int = 4,
  optBufSize: Int = 32
) extends Module {
  val io = IO(new Bundle {
    val in      = Flipped(Decoupled(new ControllerRequest))
    val out     = Decoupled(new ControllerResponse)
    val memCmd  = Decoupled(new PhysicalMemoryCommand)
    val phyResp = Flipped(Decoupled(new PhysicalMemoryResponse))

    // Monitoring
    val rankState         = Output(Vec(params.numberOfRanks, UInt(3.W)))
    val reqQueueCount     = Output(UInt(log2Ceil(queueSize + 1).W))
    val respQueueCount    = Output(UInt(4.W))
    val fsmReqQueueCounts = Output(Vec(params.numberOfRanks * params.numberOfBankGroups * params.numberOfBanks, UInt(log2Ceil(optBufSize + 1).W)))
  })

  // ------ Global FIFOs ------
  val reqQueue  = Module(new Queue(new ControllerRequest, entries = queueSize))
  val respQueue = Module(new Queue(new ControllerResponse, entries = queueSize))
  reqQueue.io.enq <> io.in
  io.out        <> respQueue.io.deq
  io.reqQueueCount := reqQueue.io.count
  io.respQueueCount:= respQueue.io.count

  // ------ DRAM command queue ------
  val cmdQueue = Module(new Queue(new PhysicalMemoryCommand, entries = memoryCommandQueueSize))
  io.memCmd    <> cmdQueue.io.deq

  // ------ Instantiate FSMs ------
  val banksPerRank  = params.numberOfBankGroups * params.numberOfBanks
  val totalBankFSMs = params.numberOfRanks * banksPerRank
  val fsmVec = VecInit(Seq.tabulate(totalBankFSMs) { i =>
    val r   = i / banksPerRank
    val rem = i % banksPerRank
    val g   = rem / params.numberOfBanks
    val b   = rem % params.numberOfBanks
    val loc = LocalConfigurationParameters(channelIndex, r, g, b)
    Module(new MemoryControllerFSM(bankParams, loc, params, trackPerformance)).io
  })

  // ------ Demux incoming requests into optimizers ------
  val multiDeq = Module(new MultiDeqQueue(params, banksPerRank, totalBankFSMs, queueSize))
  multiDeq.io.enq <> reqQueue.io.deq

  // Instantiate one optimizer per FSM
  val optimizers = Seq.tabulate(totalBankFSMs) { i =>
    val opt = Module(new FsmRequestOptimizer(optBufSize))
    // feed optimizer from the demux
    opt.io.in <> multiDeq.io.deq(i)
    // forward optimized requests into the FSM
    fsmVec(i).req <> opt.io.outReq
    // monitor buffer occupancy
    io.fsmReqQueueCounts(i) := opt.io.bufCount
    opt
  }

  // ------ Command arbitration from FSMs ------
  val cmdArb = Module(new RRArbiter(new PhysicalMemoryCommand, totalBankFSMs))
  for (i <- 0 until totalBankFSMs) {
    cmdArb.io.in(i) <> fsmVec(i).cmdOut
  }
  cmdQueue.io.enq <> cmdArb.io.out

  // ------ Response routing from DRAM back into FSMs ------
  val respDecoder = Module(new AddressDecoder(params))
  respDecoder.io.addr := io.phyResp.bits.addr
  val respFlat = respDecoder.io.rankIndex   * banksPerRank.U +
                 respDecoder.io.bankGroupIndex * params.numberOfBanks.U +
                 respDecoder.io.bankIndex
  for (i <- 0 until totalBankFSMs) {
    val isTgt = respFlat === i.U
    fsmVec(i).phyResp.valid := io.phyResp.valid && isTgt
    fsmVec(i).phyResp.bits  := io.phyResp.bits
    fsmVec(i).phyResp.ready := true.B
  }
  io.phyResp.ready := fsmVec(respFlat).phyResp.ready

  // ------ Two separate RRArbiters for responses ------
  // 1) FSM responses
  val fsmRespArb = Module(new RRArbiter(new ControllerResponse, totalBankFSMs))
  for (i <- 0 until totalBankFSMs) {
    fsmRespArb.io.in(i) <> fsmVec(i).resp
  }
  // 2) Optimizer short-circuit responses
  val optRespArb = Module(new RRArbiter(new ControllerResponse, totalBankFSMs))
  for (i <- 0 until totalBankFSMs) {
    optRespArb.io.in(i) <> optimizers(i).io.outResp
  }

  // ------ Final priority arbiter: opt > FSM ------
  val finalArb = Module(new Arbiter(new ControllerResponse, 2))
  // index 0 has highest priority
  finalArb.io.in(0) <> optRespArb.io.out
  finalArb.io.in(1) <> fsmRespArb.io.out

  // enqueue into the global response queue
  respQueue.io.enq <> finalArb.io.out

  // ------ Optional performance tracker ------
  if (trackPerformance) {
    val tracker = Module(new CommandQueuePerformanceStatistics)
    tracker.io.in_fire  := cmdQueue.io.enq.fire
    tracker.io.in_bits  := cmdQueue.io.enq.bits
    tracker.io.out_fire := io.phyResp.fire
    tracker.io.out_bits := io.phyResp.bits
  }

  // ------ Rank-state aggregation ------
  for (r <- 0 until params.numberOfRanks) {
    val slice = fsmVec.slice(r * banksPerRank, (r + 1) * banksPerRank)
    io.rankState(r) := slice.map(_.stateOut).reduce(_ max _)
  }
}
