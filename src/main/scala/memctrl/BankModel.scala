// File: src/main/scala/memctrl/BankModel.scala
package memctrl

import chisel3._
import chisel3.util._
import chisel3.assert

/** Single HBM2 1T DRAM bank with FSM‑based Decoupled processing **/
class DRAMBank(params: DRAMBankParameters) extends PhysicalMemoryModuleBase {
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

  // Memory array
  val mem                  = Mem(params.addressSpaceSize, UInt(32.W))

  // Address slicing widths
  val rowWidth             = log2Ceil(params.numRows)
  val colWidth             = log2Ceil(params.numCols)

  // Helpers
  def elapsed(s: UInt, d: Int): Bool = (cycleCounter - s) >= d.U

  // Default I/O assignments
  cmd.ready      := (state === sIdle) && !refreshInProg
  resp.valid     := false.B
  resp.bits.addr := pendingCmd.addr
  resp.bits.data := params.ackData

  // ----- Command acceptance -----
  when (state === sIdle && cmd.fire && cmd.bits.cs === false.B) {
    pendingCmd := cmd.bits
    state      := sProc
    printf("\n\n[DRAM] Received a Command %d %d %d %d \n\n",
           cmd.bits.cs, cmd.bits.ras, cmd.bits.cas, cmd.bits.we)
  } .elsewhen (resp.fire) {
    // go back to Idle and allow next command
    printf("[DRAM] Response fired.\n")
    state := sIdle
  }

  // Decode active‑low fields from pendingCmd
  val cs_p        = !pendingCmd.cs
  val ras_p       = !pendingCmd.ras
  val cas_p       = !pendingCmd.cas
  val we_p        = !pendingCmd.we

  val doRefresh   = cs_p && ras_p && cas_p && !we_p
  val doActivate  = cs_p && ras_p && !cas_p && !we_p
  val doRead      = cs_p && !ras_p && cas_p && !we_p
  val doWrite     = cs_p && !ras_p && cas_p && we_p
  val doPrecharge = cs_p && ras_p && !cas_p && we_p

  val reqRow = pendingCmd.addr(31, 32 - rowWidth)
  val reqCol = pendingCmd.addr(colWidth - 1, 0)

  // ----- Processing -----

  // If we fail to meet the refresh deadline, bail out immedietly.
  // assert(refreshCntr <= params.tRFC.U)

  when (state === sProc) {
    // Refresh
    when (!refreshInProg && doRefresh) {
      refreshInProg := true.B
      refreshCntr   := params.tRFC.U

    // Precharge
    } .elsewhen (doPrecharge && !refreshInProg &&
                 elapsed(lastActivate, params.tRAS) &&
                 elapsed(lastPrecharge, params.tRP)) {
      rowActive     := false.B
      lastPrecharge := cycleCounter
      resp.valid    := true.B

    // Activate
    } .elsewhen (doActivate && !refreshInProg) {
      val oldest = activateTimes(actPtr)
      when (elapsed(oldest, params.tFAW) && elapsed(lastActivate, params.tRRD_L)) {
        rowActive             := true.B
        activeRow             := reqRow
        lastActivate          := cycleCounter
        activateTimes(actPtr) := cycleCounter
        actPtr                := actPtr + 1.U
        resp.valid            := true.B
      }

    // Read
    } .elsewhen (doRead && !refreshInProg && rowActive) {
      when (elapsed(lastActivate, params.tRCDRD) &&
            elapsed(lastReadEnd, params.tCCD_L) &&
            elapsed(lastWriteEnd, params.tWTR_L)) {
        printf("[DRAM] Read with cc %d for active row %d, col %d, data=%d\n",
               cycleCounter, activeRow, reqCol,
               mem.read(activeRow * params.numCols.U + reqCol))
        resp.bits.data := mem.read(activeRow * params.numCols.U + reqCol)
        lastReadEnd    := cycleCounter + params.CL.U
        resp.valid     := true.B
      }

    // Write
    } .elsewhen (doWrite && !refreshInProg && rowActive) {
      when (elapsed(lastActivate, params.tRCDWR) &&
            elapsed(lastWriteEnd, params.tCCD_L)) {
        printf("[DRAM] Write with cc %d data=%d @ addr %d, row=%d, col=%d\n",
               cycleCounter, pendingCmd.data, pendingCmd.addr, activeRow, reqCol)
        mem.write(activeRow * params.numCols.U + reqCol, pendingCmd.data)
        lastWriteEnd    := cycleCounter + params.CWL.U + params.tWR.U
        resp.bits.data  := pendingCmd.data
        resp.valid      := true.B
      }
    }

    // Complete refresh
    when (refreshInProg) {
      printf("[DRAM] Refresh with cc %d\n", cycleCounter)
      refreshCntr := refreshCntr - 1.U
      when (refreshCntr === 1.U) {
        refreshInProg := false.B
        lastRefresh   := cycleCounter
        rowActive     := false.B
        resp.valid    := true.B
      }
    }
  }
}
