package memctrl

import chisel3._
import chisel3.util._

class MemoryControllerFSM(params: DRAMBankParams) extends Module {
  val io = IO(new Bundle {
    val req     = Flipped(Decoupled(new ControllerRequest))  // Input request
    val resp    = Decoupled(new ControllerResponse)            // Response output
    val cmdOut  = Decoupled(new MemCmd)                        // Command output
    val phyResp = Flipped(Decoupled(new PhysicalMemResponse))  // Memory response
  })

  // Internal registers
  val reqReg          = Reg(new ControllerRequest)
  val requestActive   = RegInit(false.B)
  val issuedAddrReg   = RegInit(0.U(32.W))
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
  // By default, assume an active command (cs low) unless overridden.
  cmdReg.cs   := false.B
  cmdReg.ras  := false.B
  cmdReg.cas  := false.B
  cmdReg.we   := false.B

  // In Idle, no command is issued: drive cs high.
  when(state === sIdle) {
    cmdReg.cs := true.B
  }

  io.cmdOut.bits  := cmdReg
  // Issue command whenever not idle.
  io.cmdOut.valid := (state =/= sIdle)

  // Updated response format.
  val respReg = Wire(new ControllerResponse)
  respReg.data  := responseDataReg
  respReg.rd_en := reqReg.rd_en
  respReg.wr_en := reqReg.wr_en
  respReg.addr  := reqReg.addr
  respReg.wdata := reqReg.wdata

  io.resp.bits  := respReg
  io.resp.valid := (state === sDone)

  // Latch new request when idle.
  when(state === sIdle && !requestActive && io.req.valid) {
    reqReg := io.req.bits
    requestActive := true.B
  }
  io.req.ready := (state === sIdle) && !requestActive

  // FSM transition signals.
  val nextStateWire   = WireDefault(state)
  val nextCounterWire = WireDefault(0.U(params.counterSize.W))

  switch(state) {
    is(sIdle) {
      printf("IDLING\n")
      // No active command.
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
      // For read, drive: cs = 0, ras = 1, cas = 0, we = 1.
      cmdReg.cs  := false.B
      cmdReg.ras := true.B
      cmdReg.cas := false.B
      cmdReg.we  := true.B
      issuedAddrReg := reqReg.addr
      // Wait for the command to be issued.
      when(io.cmdOut.fire) {
        nextStateWire := sReadPending
        nextCounterWire := params.readPendingDelay.U
      }
    }
    is(sReadPending) {
      printf("READ-PEND-ing\n")
      
      // Ensure correct memory command signals
      cmdReg.ras := false.B
      cmdReg.cas := true.B
      cmdReg.we  := false.B

      // Decrement counter and check for timeout
      nextCounterWire := counter - 1.U

      when(io.phyResp.valid && (io.phyResp.bits.addr === issuedAddrReg)) {
        nextStateWire   := sPrecharge
        responseDataReg := io.phyResp.bits.data
      }.elsewhen(counter === 0.U) {  // Timeout case
        printf("TIMEOUT in READ-PEND-ing, moving to sPrecharge\n")
        nextStateWire := sPrecharge
      }
    }
    is(sWriteIssue) {
      printf("WRITE-ISSUE-ing\n")
      // For write, drive: cs = 0, ras = 1, cas = false, we = 0.
      cmdReg.cs  := false.B
      cmdReg.ras := true.B
      cmdReg.cas := false.B
      cmdReg.we  := false.B
      issuedAddrReg := reqReg.addr
      // Wait for command handshake rather than a physical response.
      when(io.cmdOut.fire) {
        nextStateWire := sWritePending
        nextCounterWire := params.writePendingDelay.U
      }
    }
    is(sWritePending) {
      printf("WRITE-PEND-ing\n")
      // Instead of waiting on a physical response (which may not be generated for writes),
      // wait until the counter expires.
      when(counter === 0.U) {
        nextStateWire   := sPrecharge
        nextCounterWire := params.prechargeDelay.U
        responseDataReg := reqReg.wdata // Echo the written data.
      }
    }
    is(sPrecharge) {
      // For precharge, the expected pattern is: cs = 0, ras = 0, cas = 1, we = 0.
      cmdReg.cs  := false.B
      cmdReg.ras := false.B
      cmdReg.cas := true.B
      cmdReg.we  := false.B
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
      // For refresh, the expected pattern is: cs = 0, ras = 0, cas = 0, we = 1.
      cmdReg.cs  := false.B
      cmdReg.ras := false.B
      cmdReg.cas := false.B
      cmdReg.we  := true.B
      nextCounterWire := params.idleDelay.U
      when(io.phyResp.valid) {
        nextStateWire := sIdle
      }
    }
  }

  // Update state and counters.
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
