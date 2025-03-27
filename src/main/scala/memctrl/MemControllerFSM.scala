package memctrl

import chisel3._
import chisel3.util._

//----------------------------------------------------------------------
// Memory Controller FSM Module
//----------------------------------------------------------------------

class MemoryControllerFSM(
  val tRCD:              Int = 5,
  val tCL:               Int = 5,
  val tPRE:              Int = 10,
  val tREFRESH:          Int = 10,
  val REFRESH_CYCLE_COUNT:Int = 200,
  val COUNTER_SIZE:      Int = 32,
  val IDLE_DELAY:        Int = 0,
  val READ_ISSUE_DELAY:  Int = 5,
  val WRITE_ISSUE_DELAY: Int = 5,
  val READ_PENDING_DELAY:Int = 5,
  val WRITE_PENDING_DELAY:Int = 5,
  val PRECHARGE_DELAY:   Int = 10,
  val REFRESH_DELAY:     Int = 10
) extends Module {
  val io = IO(new Bundle {
    // Input request from top-level FSM demux.
    val req  = Flipped(Decoupled(new ControllerRequest))
    // Response back to the unified response queue.
    val resp = Decoupled(new ControllerResponse)

    // Command output for this FSM instance (to be merged with others)
    val cmdOut = Decoupled(new MemCmd)

    // Physical memory response channel (ready/valid interface)
    val phyResp = Flipped(Decoupled(new PhysicalMemResponse))
  })

  // Provide default assignments for the sink bundle fields.
  // This avoids "not fully initialized" errors.
//   io.req.bits := 0.U.asTypeOf(new ControllerRequest)
//   io.phyResp.bits := 0.U.asTypeOf(new PhysicalMemResponse)

  // Internal registers to latch a request and track the issued address.
  val reqReg        = Reg(new ControllerRequest)
  val requestActive = RegInit(false.B)
  val issuedAddrReg = RegInit(0.U(32.W))

  // FSM states.
  val sIdle :: sReadIssue :: sReadPending :: sWriteIssue :: sWritePending :: sPrecharge :: sDone :: sRefresh :: Nil = Enum(8)
  val state = RegInit(sIdle)
  val counter = RegInit(0.U(COUNTER_SIZE.W))
  val refreshDelayCounter = RegInit(0.U(COUNTER_SIZE.W))
  val responseDataReg = RegInit(0.U(32.W))

  // Default memory command generation.
  val cmdReg = Wire(new MemCmd)
  cmdReg.addr := reqReg.addr
  cmdReg.data := Mux(reqReg.wr_en, reqReg.wdata, 0.U)
  cmdReg.cs   := false.B
  cmdReg.ras  := false.B
  cmdReg.cas  := false.B
  cmdReg.we   := false.B

  io.cmdOut.bits  := cmdReg
  io.cmdOut.valid := (state =/= sIdle) // For example, valid when active

  // Build response.
  val respReg = Wire(new ControllerResponse)
  respReg.data         := responseDataReg
  respReg.done         := (state === sDone)
  respReg.ctrllerstate := state.asUInt

  io.resp.bits  := respReg
  io.resp.valid := (state === sDone)

  // Latch new request when idle.
  when(state === sIdle && !requestActive && io.req.valid) {
    reqReg := io.req.bits
    requestActive := true.B
  }
  io.req.ready := (state === sIdle) && !requestActive

  // Default next state signals.
  val nextStateWire   = WireDefault(state)
  val nextCounterWire = WireDefault(0.U(COUNTER_SIZE.W))

  // Main FSM logic.
  switch(state) {
    is(sIdle) {
      cmdReg.cs := true.B
      when(refreshDelayCounter + tREFRESH.U >= REFRESH_CYCLE_COUNT.U) {
        nextStateWire   := sRefresh
        nextCounterWire := REFRESH_DELAY.U
      } .elsewhen(requestActive) {
        when(reqReg.rd_en) {
          nextStateWire   := sReadIssue
          nextCounterWire := READ_ISSUE_DELAY.U
        } .elsewhen(reqReg.wr_en) {
          nextStateWire   := sWriteIssue
          nextCounterWire := WRITE_ISSUE_DELAY.U
        }
      }
    }
    is(sReadIssue) {
      cmdReg.cas := true.B
      cmdReg.we  := true.B
      issuedAddrReg := reqReg.addr
      nextCounterWire := READ_PENDING_DELAY.U
      when(io.phyResp.valid && (io.phyResp.bits.addr === issuedAddrReg)) {
        nextStateWire := sReadPending
      }
    }
    is(sReadPending) {
      cmdReg.ras := true.B
      cmdReg.we  := true.B
      nextCounterWire := PRECHARGE_DELAY.U
      when(io.phyResp.valid && (io.phyResp.bits.addr === issuedAddrReg)) {
        nextStateWire   := sPrecharge
        responseDataReg := io.phyResp.bits.data
      }
    }
    is(sWriteIssue) {
      cmdReg.cas := true.B
      cmdReg.we  := true.B
      issuedAddrReg := reqReg.addr
      nextCounterWire := WRITE_PENDING_DELAY.U
      when(io.phyResp.valid && (io.phyResp.bits.addr === issuedAddrReg)) {
        nextStateWire := sWritePending
      }
    }
    is(sWritePending) {
      cmdReg.ras := true.B
      nextCounterWire := PRECHARGE_DELAY.U
      when(io.phyResp.valid && (io.phyResp.bits.addr === issuedAddrReg)) {
        nextStateWire   := sPrecharge
        responseDataReg := reqReg.wdata
      }
    }
    is(sPrecharge) {
      cmdReg.cas := true.B
      nextCounterWire := IDLE_DELAY.U
      when(io.phyResp.valid && (io.phyResp.bits.addr === issuedAddrReg)) {
        nextStateWire := sDone
      }
    }
    is(sDone) {
      nextCounterWire := IDLE_DELAY.U
      when(io.resp.fire) {  // Correct syntax: use .fire without parentheses.
        nextStateWire := sIdle
        requestActive := false.B
      }
    }
    is(sRefresh) {
      cmdReg.we := true.B
      nextCounterWire := IDLE_DELAY.U
      when(io.phyResp.valid) {
        nextStateWire := sIdle
      }
    }
  }

  // State update logic.
  when(reset.asBool) {
    state               := sIdle
    counter             := 0.U
    refreshDelayCounter := 0.U
    responseDataReg     := 0.U
    requestActive       := false.B
  } .otherwise {
    when(state =/= nextStateWire) {
      counter             := nextCounterWire
      refreshDelayCounter := Mux(state === sRefresh, 0.U, refreshDelayCounter + 1.U)
    } .otherwise {
      counter             := counter - 1.U
      refreshDelayCounter := refreshDelayCounter + 1.U
    }
    state := nextStateWire
  }

  io.phyResp.ready := true.B
}
