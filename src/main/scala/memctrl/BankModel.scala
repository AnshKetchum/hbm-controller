// File: src/main/scala/memctrl/BankModel.scala
package memctrl

import chisel3._
import chisel3.util._

/** Single HBM2 bank with FSM‑based Decoupled processing **/
class DRAMBank(params: DRAMBankParameters) extends Module {
  val io = IO(new PhysicalMemoryIO)
  val cmd  = io.memCmd
  val resp = io.phyResp

  // FSM states
  val sIdle :: sProc :: Nil = Enum(2)
  val state                = RegInit(sIdle)

  // Latch for the current command
  val pendingCmd           = Reg(new PhysicalMemoryCommand)

  // Timing & counters
  val cycleCounter         = RegInit(0.U(64.W))
  cycleCounter            := cycleCounter + 1.U

  val lastActivate         = RegInit(0.U(64.W))
  val lastPrecharge        = RegInit(0.U(64.W))
  val lastReadEnd          = RegInit(0.U(64.W))
  val lastWriteEnd         = RegInit(0.U(64.W))
  val lastRefresh          = RegInit(0.U(64.W))
  val activateTimes        = Reg(Vec(4, UInt(64.W)))
  val actPtr               = RegInit(0.U(2.W))

  val refreshInProg        = RegInit(false.B)
  val refreshCntr          = RegInit(0.U(32.W))

  val rowActive            = RegInit(false.B)
  val activeRow            = RegInit(0.U(log2Ceil(params.numRows).W))

  val mem                  = Mem(params.addressSpaceSize, UInt(32.W))
  val readValidReg         = RegInit(false.B)
  val readDataReg          = Reg(UInt(32.W))

  // Address slicing widths
  val rowWidth             = log2Ceil(params.numRows)
  val colWidth             = log2Ceil(params.numCols)

  // Helpers
  def elapsed(s: UInt, d: Int): Bool = (cycleCounter - s) >= d.U

  // Default I/O assignments
  cmd.ready  := (state === sIdle) && !refreshInProg
  resp.valid := false.B
  resp.bits.addr := pendingCmd.addr
  resp.bits.data := params.ackData

  // ----- Command acceptance -----
  when (state === sIdle && cmd.fire) {
    pendingCmd := cmd.bits
    state      := sProc
  }

  // Decode active‑low fields from pendingCmd
  val cs_p   = !pendingCmd.cs
  val ras_p  = !pendingCmd.ras
  val cas_p  = !pendingCmd.cas
  val we_p   = !pendingCmd.we

  val doRefresh   = cs_p && ras_p && cas_p && !we_p
  val doActivate  = cs_p && ras_p && !cas_p && !we_p
  val doRead      = cs_p && !ras_p && cas_p && !we_p
  val doWrite     = cs_p && !ras_p && cas_p && we_p
  val doPrecharge = cs_p && ras_p && !cas_p && we_p

  val reqRow = pendingCmd.addr(31, 32 - rowWidth)
  val reqCol = pendingCmd.addr(colWidth - 1, 0)

  // ----- Processing -----
  when (state === sProc) {
    // Start refresh
    when (!refreshInProg && doRefresh) {
      refreshInProg := true.B
      refreshCntr   := params.tRFC.U
    }
    // Precharge
    .elsewhen (doPrecharge && !refreshInProg &&
               elapsed(lastActivate, params.tRAS) &&
               elapsed(lastPrecharge, params.tRP)) {
      rowActive     := false.B
      lastPrecharge := cycleCounter
      resp.valid    := true.B
    }
    // Activate
    .elsewhen (doActivate && !refreshInProg) {
      val oldest = activateTimes(actPtr)
      when (elapsed(oldest, params.tFAW) && elapsed(lastActivate, params.tRRD_L)) {
        rowActive              := true.B
        activeRow              := reqRow
        lastActivate           := cycleCounter
        activateTimes(actPtr)  := cycleCounter
        actPtr                 := actPtr + 1.U
        resp.valid             := true.B
      }
    }
    // Read
    .elsewhen (doRead && !refreshInProg &&
               rowActive &&
               elapsed(lastActivate, params.tRCDRD) &&
               elapsed(lastReadEnd, params.tCCD_L) &&
               elapsed(lastWriteEnd, params.tWTR_L)) {
      readDataReg  := mem.read(activeRow * params.numCols.U + reqCol)
      readValidReg := true.B
      lastReadEnd  := cycleCounter + params.CL.U
    }
    // Write
    .elsewhen (doWrite && !refreshInProg &&
               rowActive &&
               elapsed(lastActivate, params.tRCDWR) &&
               elapsed(lastWriteEnd, params.tCCD_L)) {
      mem.write(activeRow * params.numCols.U + reqCol, pendingCmd.data)
      lastWriteEnd := cycleCounter + params.CWL.U + params.tWR.U
      resp.bits.data := pendingCmd.data
      resp.valid     := true.B
    }

    // Complete refresh
    when (refreshInProg) {
      refreshCntr := refreshCntr - 1.U
      when (refreshCntr === 1.U) {
        refreshInProg := false.B
        lastRefresh   := cycleCounter
        rowActive     := false.B
        resp.valid    := true.B
      }
    }
  }

  // ----- Read response -----
  when (readValidReg) {
    resp.bits.data := readDataReg
    resp.valid     := true.B
    readValidReg   := false.B
  }

  // ----- Response handshake -----
  when (resp.fire) {
    // go back to Idle and allow next command
    state := sIdle
  }
}
