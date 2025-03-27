package memctrl

import chisel3._
import chisel3.util._

//----------------------------------------------------------------------
// Top-level interface bundles (renamed)
//----------------------------------------------------------------------

/** Controller Request interface **/
class ControllerRequest extends Bundle {
  val rd_en = Bool()
  val wr_en = Bool()
  val addr  = UInt(32.W)
  val wdata = UInt(32.W)
}

/** Controller Response interface **/
class ControllerResponse extends Bundle {
  val data         = UInt(32.W)
  val done         = Bool()
  val ctrllerstate = UInt(5.W)
}

/** Memory Command interface (to external memory) **/
class MemCmd extends Bundle {
  val addr = UInt(32.W)
  val data = UInt(32.W)
  val cs   = Bool()
  val ras  = Bool()
  val cas  = Bool()
  val we   = Bool()
}

/** Physical Memory Response interface **/
class PhysicalMemResponse extends Bundle {
  val addr = UInt(32.W)
  val data = UInt(32.W)
}

//----------------------------------------------------------------------
// Top-Level MultiRank Memory Controller Module
//----------------------------------------------------------------------

class MultiRankMemoryController(
  numberOfRanks: Int = 2,
  numberofBankGroups: Int = 2,
  numberOfBanks: Int = 2
) extends Module {
  val io = IO(new Bundle {
    // Unified user interface.
    val in  = Flipped(Decoupled(new ControllerRequest))
    val out = Decoupled(new ControllerResponse)

    // Unified memory command interface.
    val memCmd = Decoupled(new MemCmd)

    // Unified physical memory response channel.
    val phyResp = Flipped(Decoupled(new PhysicalMemResponse))
  })

  // Create unified request and response queues.
  val reqQueue  = Module(new Queue(new ControllerRequest, entries = 8))
  reqQueue.io.enq <> io.in

  val respQueue = Module(new Queue(new ControllerResponse, entries = 8))
  io.out <> respQueue.io.deq

  // Create a unified command queue.
  val cmdQueue  = Module(new Queue(new MemCmd, entries = 16))
  io.memCmd <> cmdQueue.io.deq

  // Instantiate FSMs for each rank.
  val fsmVec = VecInit(Seq.fill(numberOfRanks) {
    Module(new MemoryControllerFSM()).io
  })

  //-------------------------------------------------------------------------
  // Address Decoding: Extract Rank Index
  //-------------------------------------------------------------------------
  val rankBits      = log2Ceil(numberOfRanks)
  val bankGroupBits = log2Ceil(numberofBankGroups)
  val bankBits      = log2Ceil(numberOfBanks)
  val rankShift     = bankBits + bankGroupBits
  def extractRank(addr: UInt): UInt = addr(rankShift + rankBits - 1, rankShift)

  //-------------------------------------------------------------------------
  // Connect each FSM's command output to an arbiter.
  //-------------------------------------------------------------------------
  val cmdArb = Module(new RRArbiter(new MemCmd, numberOfRanks))
  for (i <- 0 until numberOfRanks) {
    cmdArb.io.in(i) <> fsmVec(i).cmdOut
  }
  cmdQueue.io.enq <> cmdArb.io.out

  //-------------------------------------------------------------------------
  // Broadcast the phyResp signal to all FSMs.
  // Here we manually assign the valid and bits, and tie each FSM's ready to true.
  for (fsm <- fsmVec) {
    fsm.phyResp.valid := io.phyResp.valid
    fsm.phyResp.bits  := io.phyResp.bits
    fsm.phyResp.ready := true.B
  }

  //-------------------------------------------------------------------------
  // Provide default assignments for each FSM's req.bits to avoid uninitialized sinks.
  for (i <- 0 until numberOfRanks) {
    fsmVec(i).req.bits := 0.U.asTypeOf(new ControllerRequest)
  }

  //-------------------------------------------------------------------------
  // Demux incoming requests to the correct FSM based on rank.
  val reqDeqValid = Wire(Vec(numberOfRanks, Bool()))
  for (i <- 0 until numberOfRanks) {
    // By default, the request interface is not valid.
    fsmVec(i).req.valid := false.B
    reqDeqValid(i) := false.B
  }
  when(reqQueue.io.deq.valid) {
    val targetRank = extractRank(reqQueue.io.deq.bits.addr)
    for (i <- 0 until numberOfRanks) {
      when(targetRank === i.U) {
        when(fsmVec(i).req.ready) {
          fsmVec(i).req.valid := true.B
          fsmVec(i).req.bits  := reqQueue.io.deq.bits
          reqDeqValid(i)      := true.B
        }
      }
    }
  }
  reqQueue.io.deq.ready := reqDeqValid.reduce(_ || _)

  //-------------------------------------------------------------------------
  // Merge responses from FSMs using an arbiter.
  val arbResp = Module(new RRArbiter(new ControllerResponse, numberOfRanks))
  for (i <- 0 until numberOfRanks) {
    arbResp.io.in(i) <> fsmVec(i).resp
  }
  respQueue.io.enq <> arbResp.io.out

  io.phyResp.ready := true.B

}
