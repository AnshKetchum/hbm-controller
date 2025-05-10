package memctrl

import chisel3._
import chisel3.util._

/**
 * Memory controller with one FSM per physical bank (across ranks, bank groups, and banks),
 * now using MultiDeqQueue to burst-demux requests.
 */
class MultiRankMemoryController(
  params: MemoryConfigurationParameters,
  bankParams: DRAMBankParameters,
  trackPerformance: Boolean = false,
  channelIndex: Int = 0,
  queueSize: Int = 128
) extends Module {
  val io = IO(new Bundle {
    val in      = Flipped(Decoupled(new ControllerRequest))
    val out     = Decoupled(new ControllerResponse)
    val memCmd  = Decoupled(new PhysicalMemoryCommand)
    val phyResp = Flipped(Decoupled(new PhysicalMemoryResponse))

    // Monitoring
    val rankState        = Output(Vec(params.numberOfRanks, UInt(3.W)))
    val reqQueueCount    = Output(UInt(log2Ceil(queueSize+1).W))
    val respQueueCount   = Output(UInt(4.W))
    val fsmReqQueueCounts= Output(Vec(params.numberOfRanks * params.numberOfBankGroups * params.numberOfBanks, UInt(log2Ceil(queueSize+1).W)))
  })

  // ------ Global request & response FIFOs ------
  val reqQueue  = Module(new Queue(new ControllerRequest, entries = queueSize))
  val respQueue = Module(new Queue(new ControllerResponse, entries = 128))
  reqQueue.io.enq <> io.in
  io.out          <> respQueue.io.deq
  io.reqQueueCount:= reqQueue.io.count
  io.respQueueCount:= respQueue.io.count

  // ------ Physical command queue ------
  val cmdQueue = Module(new Queue(new PhysicalMemoryCommand, entries = 2048))
  io.memCmd    <> cmdQueue.io.deq

  // ------ Bank/FSM setup ------
  val banksPerRank  = params.numberOfBankGroups * params.numberOfBanks
  val totalBankFSMs = params.numberOfRanks * banksPerRank

  // Instantiate one FSM per bank
  val fsmVec = VecInit(Seq.tabulate(totalBankFSMs) { i =>
    val r   = i / banksPerRank
    val rem = i % banksPerRank
    val g   = rem / params.numberOfBanks
    val b   = rem % params.numberOfBanks
    val loc = LocalConfigurationParameters(channelIndex, r, g, b)
    Module(new MemoryControllerFSM(bankParams, loc, params, trackPerformance)).io
  })

  // ------ Multi-dequeue demux into per-FSM queues ------
  val multiDeq = Module(new MultiDeqQueue(params, banksPerRank, totalBankFSMs, queueSize))
  multiDeq.io.enq <> reqQueue.io.deq

  // hook up demux outputs directly into FSM request ports
  for (i <- 0 until totalBankFSMs) {
    fsmVec(i).req <> multiDeq.io.deq(i)
    io.fsmReqQueueCounts(i) := multiDeq.io.counts(i)
  }

  // ------ Command arbitration from FSMs ------
  val cmdArb = Module(new RRArbiter(new PhysicalMemoryCommand, totalBankFSMs))
  for (i <- 0 until totalBankFSMs) {
    cmdArb.io.in(i) <> fsmVec(i).cmdOut
  }
  cmdQueue.io.enq <> cmdArb.io.out

  // ------ Response routing back to FSMs ------
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

  // ------ Collect responses from FSMs ------
  val respArb = Module(new RRArbiter(new ControllerResponse, totalBankFSMs))
  for (i <- 0 until totalBankFSMs) {
    respArb.io.in(i) <> fsmVec(i).resp
  }
  respQueue.io.enq.valid := respArb.io.out.valid
  respQueue.io.enq.bits  := respArb.io.out.bits
  respArb.io.out.ready   := respQueue.io.enq.ready

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
    val slice = fsmVec.slice(r*banksPerRank, (r+1)*banksPerRank)
    io.rankState(r) := slice.map(_.stateOut).reduce(_ max _)
  }
}
