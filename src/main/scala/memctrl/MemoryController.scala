package memctrl

import chisel3._
import chisel3.util._

//----------------------------------------------------------------------
// Top-Level MultiRank Memory Controller Module
//----------------------------------------------------------------------
class MultiRankMemoryController(params: MemoryConfigurationParameters, bankParams: DRAMBankParameters) extends Module {
  val io = IO(new Bundle {
    // Unified user interface.
    val in  = Flipped(Decoupled(new ControllerRequest))
    val out = Decoupled(new ControllerResponse)

    // Unified memory command interface.
    val memCmd = Decoupled(new PhysicalMemoryCommand)

    // Unified physical memory response channel.
    val phyResp = Flipped(Decoupled(new PhysicalMemoryResponse))
  })

  // Create unified request and response queues.
  val reqQueue  = Module(new Queue(new ControllerRequest, entries = 8))
  reqQueue.io.enq <> io.in

  val respQueue = Module(new Queue(new ControllerResponse, entries = 8))
  io.out <> respQueue.io.deq

  // Create a unified command queue.
  val cmdQueue  = Module(new Queue(new PhysicalMemoryCommand, entries = 16))
  io.memCmd <> cmdQueue.io.deq

  // Instantiate FSMs for each rank.
  val fsmVec = VecInit(Seq.fill(params.numberOfRanks) {
    Module(new MemoryControllerFSM(bankParams)).io
  })

  // Create per-FSM request queues to decouple the unified request queue and the FSM’s readiness.
  val fsmReqQueues = VecInit(Seq.fill(params.numberOfRanks) {
    Module(new Queue(new ControllerRequest, entries = 4)).io
  })

  //-------------------------------------------------------------------------
  // Address Decoding: Extract Rank Index
  //-------------------------------------------------------------------------
  val rankBits      = log2Ceil(params.numberOfRanks)
  val bankGroupBits = log2Ceil(params.numberOfBankGroups)
  val bankBits      = log2Ceil(params.numberOfBanks)
  val rankShift     = bankBits + bankGroupBits
  def extractRank(addr: UInt): UInt = addr(rankShift + rankBits - 1, rankShift)

  // Demux incoming requests from the unified queue to the per-FSM request queues.
  // We always dequeue from reqQueue (if valid) and use the address to determine which FSM’s queue
  // to enqueue the request into. We only block the unified queue when the target per-FSM queue is full.
  val enqReadyVec = Wire(Vec(params.numberOfRanks, Bool()))
  for (i <- 0 until params.numberOfRanks) {
    // Provide default assignments to avoid uninitialized sinks.
    fsmReqQueues(i).enq.valid := false.B
    fsmReqQueues(i).enq.bits  := 0.U.asTypeOf(new ControllerRequest)
    enqReadyVec(i) := false.B
  }
  when (reqQueue.io.deq.valid) {
    val targetRank = extractRank(reqQueue.io.deq.bits.addr)
    for (i <- 0 until params.numberOfRanks) {
      when (targetRank === i.U) {
        printf("Enqueuing request to queue %d\n", targetRank)
        fsmReqQueues(i).enq.valid := true.B
        fsmReqQueues(i).enq.bits  := reqQueue.io.deq.bits
        enqReadyVec(i) := fsmReqQueues(i).enq.ready
      }
    }
  }
  reqQueue.io.deq.ready := enqReadyVec.reduce(_ || _)

  //-------------------------------------------------------------------------
  // Connect each FSM’s request input to its dedicated per-FSM request queue.
  // Each FSM will pull a request when it is free.
  //-------------------------------------------------------------------------
  for (i <- 0 until params.numberOfRanks) {
    fsmVec(i).req <> fsmReqQueues(i).deq
  }

  //-------------------------------------------------------------------------
  // Connect each FSM's command output to an arbiter.
  //-------------------------------------------------------------------------
  val cmdArb = Module(new RRArbiter(new PhysicalMemoryCommand, params.numberOfRanks))
  for (i <- 0 until params.numberOfRanks) {
    cmdArb.io.in(i) <> fsmVec(i).cmdOut
  }
  cmdQueue.io.enq <> cmdArb.io.out

  //-------------------------------------------------------------------------
  // Broadcast the phyResp signal to all FSMs.
  //-------------------------------------------------------------------------
  for (fsm <- fsmVec) {
    fsm.phyResp.valid := io.phyResp.valid
    fsm.phyResp.bits  := io.phyResp.bits
    fsm.phyResp.ready := true.B
  }

  //-------------------------------------------------------------------------
  // Merge responses from FSMs using an arbiter.
  //-------------------------------------------------------------------------
  val arbResp = Module(new RRArbiter(new ControllerResponse, params.numberOfRanks))
  for (i <- 0 until params.numberOfRanks) {
    arbResp.io.in(i) <> fsmVec(i).resp
  }
  respQueue.io.enq <> arbResp.io.out

  io.phyResp.ready := true.B
}
