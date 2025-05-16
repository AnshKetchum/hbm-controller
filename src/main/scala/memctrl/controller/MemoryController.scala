package memctrl

import chisel3._
import chisel3.util._

/** Memory controller with one FSM per physical bank (across ranks and banks), now using MultiDeqQueue to burst-demux requests. */
class MultiRankMemoryController(
  params:           MemoryConfigurationParameters,
  bankParams:       DRAMBankParameters,
  trackPerformance: Boolean = false,
  channelIndex:     Int = 0,
  queueSize:        Int = 256)
    extends Module {
  val io = IO(new Bundle {
    val in      = Flipped(Decoupled(new ControllerRequest))
    val out     = Decoupled(new ControllerResponse)
    val memCmd  = Decoupled(new PhysicalMemoryCommand)
    val phyResp = Flipped(Decoupled(new PhysicalMemoryResponse))

    // Monitoring
    val rankState         = Output(Vec(params.numberOfRanks, UInt(3.W)))
    val reqQueueCount     = Output(UInt(log2Ceil(queueSize + 1).W))
    val respQueueCount    = Output(UInt(4.W))
    val fsmReqQueueCounts = Output(
      Vec(params.numberOfRanks * params.numberOfBanks, UInt(log2Ceil(queueSize + 1).W))
    )
  })

  // ------ Global request & response FIFOs ------
  val reqQueue  = Module(new Queue(new ControllerRequest, entries = queueSize))
  val respQueue = Module(new Queue(new ControllerResponse, entries = queueSize))
  reqQueue.io.enq <> io.in
  io.out <> respQueue.io.deq
  io.reqQueueCount  := reqQueue.io.count
  io.respQueueCount := respQueue.io.count

  // ------ Physical command queue ------
  val cmdQueue = Module(new Queue(new PhysicalMemoryCommand, entries = queueSize))
  io.memCmd <> cmdQueue.io.deq

  // ------ Bank/FSM setup ------
  val banksPerRank  = params.numberOfBanks
  val totalBankFSMs = params.numberOfRanks * banksPerRank

  // Instantiate one FSM per bank
  val fsmVec = VecInit(Seq.tabulate(totalBankFSMs) { i =>
    val r   = i / banksPerRank
    val b   = i % banksPerRank
    val loc = LocalConfigurationParameters(channelIndex, r, 0, b)
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
  val respFlat = respDecoder.io.rankIndex * banksPerRank.U + respDecoder.io.bankIndex

  for (i <- 0 until totalBankFSMs) {
    val isTgt  = respFlat === i.U
    val doFire = io.phyResp.valid && fsmVec(i).phyResp.ready && isTgt

    fsmVec(i).phyResp.valid := io.phyResp.valid && isTgt
    fsmVec(i).phyResp.bits  := io.phyResp.bits

    when(doFire) {
      printf(
        "[Controller] Response routed to FSM %d (rank %d, bank %d) at cycle\n",
        i.U,
        (i / banksPerRank).U,
        (i % banksPerRank).U
      )
      printf(
        "  -> request_id = %d, data = 0x%x, addr = 0x%x\n",
        io.phyResp.bits.request_id,
        io.phyResp.bits.data,
        io.phyResp.bits.addr
      )
    }
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
    val slice = fsmVec.slice(r * banksPerRank, (r + 1) * banksPerRank)
    io.rankState(r) := slice.map(_.stateOut).reduce(_ max _)
  }
}
