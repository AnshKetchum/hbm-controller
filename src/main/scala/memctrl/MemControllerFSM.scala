package memctrl

import chisel3._
import chisel3.util._

//----------------------------------------------------------------------
// Memory Controller FSM Module
//----------------------------------------------------------------------

class MemoryControllerFSM(params: DRAMBankParams) extends Module {
  val io = IO(new Bundle {
    val req  = Flipped(Decoupled(new ControllerRequest))  // Input request
    val resp = Decoupled(new ControllerResponse)         // Response output
    val cmdOut = Decoupled(new MemCmd)                   // Command output
    val phyResp = Flipped(Decoupled(new PhysicalMemResponse)) // Memory response
  })

  // Internal registers
  val reqReg        = Reg(new ControllerRequest)
  val requestActive = RegInit(false.B)
  val issuedAddrReg = RegInit(0.U(32.W))
  val responseDataReg = RegInit(0.U(32.W))

  // FSM states
  val sIdle :: sReadIssue :: sReadPending :: sWriteIssue :: sWritePending :: sPrecharge :: sDone :: sRefresh :: Nil = Enum(8)
  val state = RegInit(sIdle)
  val counter = RegInit(0.U(params.counterSize.W))
  val refreshDelayCounter = RegInit(0.U(params.counterSize.W))

  // Default memory command
  val cmdReg = Wire(new MemCmd)
  cmdReg.addr := reqReg.addr
  cmdReg.data := Mux(reqReg.wr_en, reqReg.wdata, 0.U)
  cmdReg.cs   := false.B
  cmdReg.ras  := false.B
  cmdReg.cas  := false.B
  cmdReg.we   := false.B

  io.cmdOut.bits  := cmdReg
  io.cmdOut.valid := (state =/= sIdle) 

  // Updated response format
  val respReg = Wire(new ControllerResponse)
  respReg.data  := responseDataReg
  respReg.rd_en := reqReg.rd_en
  respReg.wr_en := reqReg.wr_en
  respReg.addr  := reqReg.addr
  respReg.wdata := reqReg.wdata

  io.resp.bits  := respReg
  io.resp.valid := (state === sDone)

  // Latch new request
  printf("Is the request valid? %d\n", io.req.valid)
  when(state === sIdle && !requestActive && io.req.valid) {
    reqReg := io.req.bits
    requestActive := true.B
  }
  io.req.ready := (state === sIdle) && !requestActive

  // FSM Logic
  val nextStateWire   = WireDefault(state)
  val nextCounterWire = WireDefault(0.U(params.counterSize.W))

  switch(state) {
    is(sIdle) {
      printf("IDLING\n")
      cmdReg.cs := true.B
      when(refreshDelayCounter + params.tREFRESH.U >= params.refreshCycleCount.U) {
        nextStateWire   := sRefresh
        nextCounterWire := params.refreshDelay.U
      } .elsewhen(requestActive) {
        when(reqReg.rd_en) {
          nextStateWire   := sReadIssue
          nextCounterWire := params.readIssueDelay.U
        } .elsewhen(reqReg.wr_en) {
          nextStateWire   := sWriteIssue
          nextCounterWire := params.writeIssueDelay.U
        }
      }
    }
    is(sReadIssue) {
      printf("READ-ISSUE-ing\n")
      cmdReg.cas := true.B
      cmdReg.we  := true.B
      issuedAddrReg := reqReg.addr
      nextCounterWire := params.readPendingDelay.U
      when(io.phyResp.valid && (io.phyResp.bits.addr === issuedAddrReg)) {
        nextStateWire := sReadPending
      }
    }
    is(sReadPending) {
      printf("READ-PEND-ing\n")
      cmdReg.ras := true.B
      cmdReg.we  := true.B
      nextCounterWire := params.prechargeDelay.U
      when(io.phyResp.valid && (io.phyResp.bits.addr === issuedAddrReg)) {
        nextStateWire   := sPrecharge
        responseDataReg := io.phyResp.bits.data
      }
    }
    is(sWriteIssue) {
      printf("WRITE-ISSUE-ing\n")
      cmdReg.cas := true.B
      cmdReg.we  := true.B
      issuedAddrReg := reqReg.addr
      nextCounterWire := params.writePendingDelay.U
      when(io.phyResp.valid && (io.phyResp.bits.addr === issuedAddrReg)) {
        nextStateWire := sWritePending
      }
    }
    is(sWritePending) {
      printf("WRITE-PEND-ing\n")
      cmdReg.ras := true.B
      nextCounterWire := params.prechargeDelay.U
      when(io.phyResp.valid && (io.phyResp.bits.addr === issuedAddrReg)) {
        nextStateWire   := sPrecharge
        responseDataReg := reqReg.wdata
      }
    }
    is(sPrecharge) {
      printf("PRECHARGE-ing\n")
      cmdReg.cas := true.B
      nextCounterWire := params.idleDelay.U
      when(io.phyResp.valid && (io.phyResp.bits.addr === issuedAddrReg)) {
        nextStateWire := sDone
      }
    }
    is(sDone) {
      printf("DONE-ing\n")
      nextCounterWire := params.idleDelay.U
      when(io.resp.fire) {  
        nextStateWire := sIdle
        requestActive := false.B
      }
    }
    is(sRefresh) {
      printf("REFRESH-ing\n")
      cmdReg.we := true.B
      nextCounterWire := params.idleDelay.U
      when(io.phyResp.valid) {
        nextStateWire := sIdle
      }
    }
  }

  // State update logic
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
