package memctrl

import chisel3._
import chisel3.util._

/**
  * Simplified optimizer that buffers WRITE requests with coalescing,
  * and short-circuits READs when a matching WRITE is pending.
  *
  * Eviction policy when full: overwrite the first valid slot.
  *
  * Workflow:
  * 1) In sIdle, on io.in.fire:
  *    – stage the request,
  *    – snapshot addrBuf, wdataBuf, reqIdBuf, validMask,
  *    – bump globalTime,
  *    – goto sHandle.
  * 2) In sHandle:
  *    – build matchVec & freeVec,
  *    – for READ+hit: emit short-circuit response & evict,
  *    – for WRITE: update/coalesce or evict-first, **then** forward to FSM,
  *    – for READ+miss: forward to FSM,
  *    – return to sIdle once downstream is ready.
  */
class FsmRequestOptimizer(val queueSize: Int = 16) extends Module {
  val io = IO(new Bundle {
    val in       = Flipped(Decoupled(new ControllerRequest))
    val outReq   = Decoupled(new ControllerRequest)
    val outResp  = Decoupled(new ControllerResponse)
    val bufCount = Output(UInt(log2Ceil(queueSize + 1).W))
  })

  // === Main write buffer ===
  val addrBuf    = Reg(Vec(queueSize, UInt(32.W)))
  val wdataBuf   = Reg(Vec(queueSize, UInt(32.W)))
  val reqIdBuf   = Reg(Vec(queueSize, UInt(32.W)))
  val validMask  = RegInit(VecInit(Seq.fill(queueSize)(false.B)))
  val globalTime = RegInit(0.U(log2Ceil(queueSize + 1).W))

  // === Snapshots for combinational separation ===
  val sAddr   = Reg(Vec(queueSize, UInt(32.W)))
  val sWdata  = Reg(Vec(queueSize, UInt(32.W)))
  val sReqId  = Reg(Vec(queueSize, UInt(32.W)))
  val sValid  = Reg(Vec(queueSize, Bool()))

  // FSM states
  val sIdle :: sHandle :: Nil = Enum(2)
  val state = RegInit(sIdle)

  // Stage incoming request
  val stagedReq = Reg(new ControllerRequest())

  // Write-buffer occupancy
  io.bufCount := PopCount(validMask)

  // Default handshakes
  io.in.ready      := state === sIdle
  io.outReq.valid  := false.B
  io.outReq.bits   := 0.U.asTypeOf(io.outReq.bits)
  io.outResp.valid := false.B
  io.outResp.bits  := 0.U.asTypeOf(io.outResp.bits)

  // 1) Idle: accept & snapshot
  when (state === sIdle && io.in.fire) {
    stagedReq := io.in.bits
    // snapshot buffer contents
    for (i <- 0 until queueSize) {
      sAddr(i)  := addrBuf(i)
      sWdata(i) := wdataBuf(i)
      sReqId(i) := reqIdBuf(i)
      sValid(i) := validMask(i)
    }
    globalTime := globalTime + 1.U
    state := sHandle
  }

  // 2) Handle: compute & dispatch
  when (state === sHandle) {
    val req = stagedReq

    // build hit & free vectors
    val matchVec = Wire(Vec(queueSize, Bool()))
    val freeVec  = Wire(Vec(queueSize, Bool()))
    for (i <- 0 until queueSize) {
      matchVec(i) := sValid(i) && req.rd_en && sAddr(i) === req.addr
      freeVec(i)  := !sValid(i)
    }
    val hasMatch = matchVec.asUInt.orR
    val matchIdx = PriorityEncoder(matchVec)
    val hasFree  = freeVec.asUInt.orR
    val freeIdx  = PriorityEncoder(freeVec)

    // eviction index when full = first valid
    val evictIdx = PriorityEncoder(sValid)

    // 2A) READ hit: short-circuit
    when (req.rd_en && hasMatch) {
        printf("[OPTIMIZER] READ hit: addr=0x%x, data=0x%x, id=%d\n", req.addr, sWdata(matchIdx), req.request_id)

      io.outResp.valid := true.B
      io.outResp.bits.rd_en      := true.B
      io.outResp.bits.wr_en      := false.B
      io.outResp.bits.addr       := sAddr(matchIdx)
      io.outResp.bits.wdata      := sWdata(matchIdx)
      io.outResp.bits.data       := sWdata(matchIdx)
      io.outResp.bits.request_id := req.request_id
      when (io.outResp.ready) {
        validMask(matchIdx) := false.B
        state := sIdle
      }

    } .elsewhen (req.wr_en) {
      // 2B) WRITE: coalesce or allocate or evict-first
      when (hasMatch) {
        // update existing
        printf("[OPTIMIZER] WRITE coalesced: addr=0x%x, data=0x%x, id=%d\n", req.addr, req.wdata, req.request_id)

        wdataBuf(matchIdx) := req.wdata
        reqIdBuf(matchIdx) := req.request_id
      } .elsewhen (hasFree) {
        // new slot
        printf("[OPTIMIZER] WRITE buffered: addr=0x%x, data=0x%x, id=%d, slot=%d\n", req.addr, req.wdata, req.request_id, freeIdx)

        addrBuf(freeIdx)   := req.addr
        wdataBuf(freeIdx)  := req.wdata
        reqIdBuf(freeIdx)  := req.request_id
        validMask(freeIdx) := true.B
      } .otherwise {
        // overwrite first valid
        printf("[OPTIMIZER] WRITE evicted: addr=0x%x, data=0x%x, id=%d, evicted_slot=%d\n", req.addr, req.wdata, req.request_id, evictIdx)

        addrBuf(evictIdx)  := req.addr
        wdataBuf(evictIdx) := req.wdata
        reqIdBuf(evictIdx) := req.request_id
      }
      // always forward the WRITE to the FSM
      io.outReq.valid := true.B
      io.outReq.bits  := req
      when (io.outReq.ready) {
        state := sIdle
      }

    } .otherwise {
      // 2C) READ miss: forward to FSM
    printf("[OPTIMIZER] READ miss: addr=0x%x, id=%d, forwarding to FSM\n", req.addr, req.request_id)

      io.outReq.valid := true.B
      io.outReq.bits  := req
      when (io.outReq.ready) {
        state := sIdle
      }
    }
  }
}
