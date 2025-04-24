package memctrl

import chisel3._
import chisel3.util._

class MultiRankMemoryController(params: MemoryConfigurationParameters, bankParams: DRAMBankParameters) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new ControllerRequest))
    val out = Decoupled(new ControllerResponse)

    val memCmd  = Decoupled(new PhysicalMemoryCommand)
    val phyResp = Flipped(Decoupled(new PhysicalMemoryResponse))

    val rankState        = Output(Vec(params.numberOfRanks, UInt(3.W)))
    val reqQueueCount    = Output(UInt(4.W))
    val respQueueCount   = Output(UInt(4.W))
    val fsmReqQueueCounts = Output(Vec(params.numberOfRanks, UInt(3.W)))
  })

  // Request and response queues
  val reqQueue  = Module(new Queue(new ControllerRequest, entries = 8))
  reqQueue.io.enq <> io.in

  val respQueue = Module(new Queue(new ControllerResponse, entries = 8))
  io.out <> respQueue.io.deq

  val cmdQueue  = Module(new Queue(new PhysicalMemoryCommand, entries = 16))
  io.memCmd <> cmdQueue.io.deq

  // FSM instantiation
  val fsmVec = VecInit(Seq.fill(params.numberOfRanks) {
    Module(new MemoryControllerFSM(bankParams)).io
  })

  for ((fsmIo, idx) <- fsmVec.zipWithIndex) {
    io.rankState(idx) := fsmIo.stateOut
  }

  val fsmReqQueues = VecInit(Seq.fill(params.numberOfRanks) {
    Module(new Queue(new ControllerRequest, entries = 4)).io
  })

  // Address decoding helper
  val rankBits      = log2Ceil(params.numberOfRanks)
  val bankGroupBits = log2Ceil(params.numberOfBankGroups)
  val bankBits      = log2Ceil(params.numberOfBanks)
  val rankShift     = bankBits + bankGroupBits
  def extractRank(addr: UInt): UInt = addr(rankShift + rankBits - 1, rankShift)

  // Demux requests to per-FSM queues
  val enqReadyVec = Wire(Vec(params.numberOfRanks, Bool()))
  for (i <- 0 until params.numberOfRanks) {
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

  // Connect FSMs to their queues
  for (i <- 0 until params.numberOfRanks) {
    fsmVec(i).req <> fsmReqQueues(i).deq
    when(fsmVec(i).req.fire) {
      printf("[MultiRankMC] FSM #%d req.fire\n", i.U)
    }
  }

  // Arbiter for command output
  val cmdArb = Module(new RRArbiter(new PhysicalMemoryCommand, params.numberOfRanks))
  for (i <- 0 until params.numberOfRanks) {
    cmdArb.io.in(i) <> fsmVec(i).cmdOut
  }
  cmdQueue.io.enq <> cmdArb.io.out

  // ----------------------
  // Address-based response routing
  // ----------------------
  val respAddrDecoder = Module(new AddressDecoder(params))
  respAddrDecoder.io.addr := io.phyResp.bits.addr
  val targetRankFromResp = respAddrDecoder.io.rankIndex

  for ((fsm, idx) <- fsmVec.zipWithIndex) {
    val isTargetRank = targetRankFromResp === idx.U
    fsm.phyResp.valid := io.phyResp.valid && isTargetRank
    fsm.phyResp.bits  := io.phyResp.bits
    fsm.phyResp.ready := true.B

    when(io.phyResp.valid && isTargetRank && fsm.phyResp.ready) {
      printf("[MultiRankMC] Response from memory accepted by FSM #%d | addr = 0x%x\n", idx.U, io.phyResp.bits.addr)
    }
  }

  io.phyResp.ready := fsmVec(targetRankFromResp).phyResp.ready

  // Response arbiter
  val arbResp = Module(new RRArbiter(new ControllerResponse, params.numberOfRanks))
  for (i <- 0 until params.numberOfRanks) {
    arbResp.io.in(i) <> fsmVec(i).resp
    when(fsmVec(i).resp.fire) {
      printf("[MultiRankMC] FSM #%d resp.fire -> arbiter (addr=0x%x)\n", i.U, fsmVec(i).resp.bits.addr)
    }
  }

  respQueue.io.enq.valid := arbResp.io.out.valid
  respQueue.io.enq.bits  := arbResp.io.out.bits
  arbResp.io.out.ready   := respQueue.io.enq.ready

  when (respQueue.io.enq.fire) {
    printf("[MultiRankMC] Response enqueued, addr=0x%x\n", respQueue.io.enq.bits.addr)
  }

  // Queue counts
  io.reqQueueCount := reqQueue.io.count
  io.respQueueCount := respQueue.io.count
  for (i <- 0 until params.numberOfRanks) {
    io.fsmReqQueueCounts(i) := fsmReqQueues(i).count
  }
}
