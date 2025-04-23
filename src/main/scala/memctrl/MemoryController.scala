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

    val rankState = Output(Vec(params.numberOfRanks, UInt(3.W)))
    val reqQueueCount = Output(UInt(4.W))
    val respQueueCount = Output(UInt(4.W))
    val fsmReqQueueCounts = Output(Vec(params.numberOfRanks, UInt(3.W)))
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

    // ------------------------------------------------
  // Hook up the state register from each FSM into io.rankState
  for ((fsmIo, idx) <- fsmVec.zipWithIndex) {
    // MemoryControllerFSM should have `state: UInt` already defined as a Register
    io.rankState(idx) := fsmIo.stateOut
  }

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
    // print just the FSM index when its request is taken
    when(fsmVec(i).req.fire) {
      printf("[MultiRankMC] FSM #%d req.fire\n", i.U)
    }
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
    // wire FSM resp into arbiter
    arbResp.io.in(i) <> fsmVec(i).resp
    // print whenever this FSM’s resp.fire() to the arbiter
    when(fsmVec(i).resp.fire) {
      printf("[MultiRankMC] FSM #%d resp.fire -> arbiter (addr=0x%x)\n",
             i.U, fsmVec(i).resp.bits.addr)
    }
  }

  // respQueue.io.enq <> arbResp.io.out
  // connect arbiter output into the respQueue, but also print on each enqueue
  respQueue.io.enq.valid := arbResp.io.out.valid
  respQueue.io.enq.bits  := arbResp.io.out.bits
  arbResp.io.out.ready    := respQueue.io.enq.ready

  // Print when a response is actually enqueued
  when (respQueue.io.enq.fire) {
    // Print the rank-group’s response address as a hex value
    printf("[MultiRankMC] Response enqueued, addr=0x%x\n", respQueue.io.enq.bits.addr)
  }

  io.phyResp.ready := true.B

    // Connect internal queue counts to IO
  io.reqQueueCount := reqQueue.io.count
  io.respQueueCount := respQueue.io.count
  for (i <- 0 until params.numberOfRanks) {
    io.fsmReqQueueCounts(i) := fsmReqQueues(i).count
  }
}
