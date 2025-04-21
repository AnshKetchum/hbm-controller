package memctrl

import chisel3._
import chisel3.util._

/**
 * Memory controller FSM for a single HBM2 rank,
 * enforcing all timing constraints from DRAMBankParams.
 */
class MemoryControllerFSM(params: DRAMBankParams) extends Module {
  val io = IO(new Bundle {
    val req     = Flipped(Decoupled(new ControllerRequest))
    val resp    = Decoupled(new ControllerResponse)
    val cmdOut  = Decoupled(new MemCmd)
    val phyResp = Flipped(Decoupled(new PhysicalMemResponse))
  })

  // --------------------------------------------------
  // Global cycle counter
  val cycleCounter = RegInit(0.U(64.W))
  cycleCounter := cycleCounter + 1.U

  // Command history timestamps
  val lastActivate    = RegInit(0.U(64.W))
  val lastPrecharge   = RegInit(0.U(64.W))
  val lastReadEnd     = RegInit(0.U(64.W))
  val lastWriteEnd    = RegInit(0.U(64.W))
  val lastRefresh     = RegInit(0.U(64.W))
  val activateTimes   = Reg(Vec(4, UInt(64.W)))
  val actPtr          = RegInit(0.U(2.W))

  // Request tracking
  val reqReg          = Reg(new ControllerRequest)
  val requestActive   = RegInit(false.B)
  val issuedAddrReg   = RegInit(0.U(32.W))
  val responseDataReg = RegInit(0.U(32.W))

  // FSM states
  val sIdle :: sActivate :: sReadIssue :: sReadPending :: sWriteIssue :: sWritePending :: sPrecharge :: sDone :: sRefresh :: Nil = Enum(9)
  val state = RegInit(sIdle)
  val counter = RegInit(0.U(32.W))

  // Decode input request
  io.req.ready := (state === sIdle) && !requestActive
  when(state === sIdle && io.req.fire) {
    reqReg := io.req.bits
    requestActive := true.B
  }

  // Default cmdOut
  val cmdReg = Wire(new MemCmd)
  cmdReg.addr := issuedAddrReg
  cmdReg.data := reqReg.wdata
  cmdReg.cs   := true.B
  cmdReg.ras  := false.B
  cmdReg.cas  := false.B
  cmdReg.we   := false.B
  io.cmdOut.bits  := cmdReg
  io.cmdOut.valid := (state =/= sIdle)

  // Default resp
  val respReg = Wire(new ControllerResponse)
  respReg.addr  := reqReg.addr
  respReg.wr_en := reqReg.wr_en
  respReg.rd_en := reqReg.rd_en
  respReg.wdata := reqReg.wdata
  respReg.data  := responseDataReg
  io.resp.bits  := respReg
  io.resp.valid := (state === sDone)

  // Helper: check if at least 'd' cycles have passed since time t
  def elapsed(since: UInt, d: UInt): Bool = (cycleCounter - since) >= d

  // State transitions
  switch(state) {
    is(sIdle) {
      // Auto-refresh if past tREFI
      when(elapsed(lastRefresh, params.tREFI.U)) {
        state := sRefresh
      } .elsewhen(requestActive) {
        state := sActivate
      }
    }
    is(sActivate) {
      // Can only activate if: tRRD_L and tFAW
      val oldest = activateTimes(actPtr)
      val canFAW = elapsed(oldest, params.tFAW.U)
      val canRRD = elapsed(lastActivate, params.tRRD_L.U)
      when(canFAW && canRRD) {
        // Issue ACT: cs=0, ras=0, cas=1, we=1
        cmdReg.cs  := false.B
        cmdReg.ras := false.B
        cmdReg.cas := true.B
        cmdReg.we  := true.B
        issuedAddrReg := reqReg.addr
        when(io.cmdOut.fire) {
          lastActivate := cycleCounter
          activateTimes(actPtr) := cycleCounter
          actPtr := actPtr + 1.U
          state := Mux(reqReg.rd_en, sReadIssue, sWriteIssue)
        }
      }
    }
    is(sReadIssue) {
      // After ACT, need tRCDRD
      when(elapsed(lastActivate, params.tRCDRD.U)) {
        // Issue RD: cs=0, ras=1, cas=0, we=1
        cmdReg.cs  := false.B
        cmdReg.ras := true.B
        cmdReg.cas := false.B
        cmdReg.we  := true.B
        when(io.cmdOut.fire) {
          issuedAddrReg := reqReg.addr
          counter := params.CL.U
          state := sReadPending
        }
      }
    }
    is(sReadPending) {
      // Wait CAS latency, tCCD_L, and tWTR_L
      when(counter === 0.U && elapsed(lastReadEnd, params.tCCD_L.U) && elapsed(lastWriteEnd, params.tWTR_L.U)) {
        // Capture data
        when(io.phyResp.fire && io.phyResp.bits.addr === issuedAddrReg) {
          responseDataReg := io.phyResp.bits.data
          lastReadEnd := cycleCounter
          state := sPrecharge
        }
      } .otherwise {
        counter := counter - 1.U
      }
    }
    is(sWriteIssue) {
      // After ACT, need tRCDWR and tCCD_L
      when(elapsed(lastActivate, params.tRCDWR.U) && elapsed(lastWriteEnd, params.tCCD_L.U)) {
        // Issue WR: cs=0, ras=1, cas=0, we=0
        cmdReg.cs  := false.B
        cmdReg.ras := true.B
        cmdReg.cas := false.B
        cmdReg.we  := false.B
        when(io.cmdOut.fire) {
          issuedAddrReg := reqReg.addr
          lastWriteEnd := cycleCounter + params.CWL.U + params.tWR.U
          state := sWritePending
        }
      }
    }
    is(sWritePending) {
      // Wait write recovery (tWR + CWL)
      when(elapsed(lastWriteEnd, 0.U)) {
        state := sPrecharge
      }
    }
    is(sPrecharge) {
      // After RD/WR, need tRTP and tRAS
      val tRTP = Mux(reqReg.rd_en, params.tRTP_L.U, params.tRTP_S.U)
      when(elapsed(lastReadEnd, tRTP) && elapsed(lastActivate, params.tRAS.U)) {
        // Issue PRE: cs=0, ras=0, cas=1, we=0
        cmdReg.cs  := false.B
        cmdReg.ras := false.B
        cmdReg.cas := true.B
        cmdReg.we  := false.B
        when(io.cmdOut.fire) {
          lastPrecharge := cycleCounter
          state := sDone
        }
      }
    }
    is(sDone) {
      when(io.resp.fire) {
        requestActive := false.B
        state := sIdle
      }
    }
    is(sRefresh) {
      // Issue REF: cs=0, ras=0, cas=0, we=1
      when(elapsed(lastRefresh, params.tREFI.U)) {
        cmdReg.cs  := false.B
        cmdReg.ras := false.B
        cmdReg.cas := false.B
        cmdReg.we  := true.B
        when(io.cmdOut.fire) {
          lastRefresh := cycleCounter
          state := sIdle
        }
      }
    }
  }

  io.phyResp.ready := true.B
}
