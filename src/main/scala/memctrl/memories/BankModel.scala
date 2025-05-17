package memctrl

import chisel3._
import chisel3.util._
import chisel3.util.log2Ceil

/** Single HBM2 1T DRAM bank with FSM-based Decoupled processing plus command-driven self-refresh */
class DRAMBank(
  params:           DRAMBankParameters,
  localConfig:      LocalConfigurationParameters,
  trackPerformance: Boolean = false)
    extends PhysicalBankModuleBase {
  // Shorthand for IO
  val cmd  = io.memCmd  // Decoupled[BankMemoryCommand]
  val resp = io.phyResp // Decoupled[BankMemoryResponse]

  // This bank's fixed indices within the channel
  private val rankIndex = localConfig.rankIndex.U
  private val bankIndex = localConfig.bankIndex.U

  // FSM states: Idle, Processing, SREF_ENTER, SREF, SREF_EXIT
  val sIdle :: sProc :: sSrefEnter :: sSref :: sSrefExit :: Nil = Enum(5)
  val state                                                     = RegInit(sIdle)

  // Latched command
  val pending = Reg(new BankMemoryCommand)

  // 64-bit cycle counter
  val cycleCounter = RegInit(0.U(64.W))
  cycleCounter := cycleCounter + 1.U

  // JEDEC timing stamps
  val lastActivate  = RegInit(0.U(64.W))
  val lastPrecharge = RegInit(0.U(64.W))
  val lastReadEnd   = RegInit(0.U(64.W))
  val lastWriteEnd  = RegInit(0.U(64.W))
  val lastRefresh   = RegInit(0.U(64.W))

  // Four-activate window (tFAW) tracking
  val activateTimes = Reg(Vec(4, UInt(64.W)))
  val actPtr        = RegInit(0.U(2.W))

  // Internal refresh tracking
  val refreshInProg = RegInit(false.B)
  val refreshCntr   = RegInit(0.U(32.W))

  // Row-buffer state (for timing only)
  val rowActive = RegInit(false.B)
  val activeRow = RegInit(0.U(log2Ceil(params.numRows).W))

  // Underlying memory array sized by full address space
  val mem = Mem(params.addressSpaceSize, UInt(32.W))

  // Helper: has `delay` cycles elapsed since `start`?
  private def elapsed(start: UInt, delay: UInt): Bool =
    (cycleCounter - start) >= delay

  // Default ready/valid signals
  cmd.ready            := (state === sIdle) && !refreshInProg && (state =/= sSref)
  resp.valid           := false.B
  resp.bits.request_id := pending.request_id
  resp.bits.addr       := pending.addr
  resp.bits.data       := pending.data

  // Decode command bits
  val cs_p  = !pending.cs
  val ras_p = !pending.ras
  val cas_p = !pending.cas
  val we_p  = !pending.we

  // Self-refresh commands
  val doSrefEnter = cs_p && ras_p && cas_p && we_p
  val doSrefExit  = cs_p && !ras_p && !cas_p && !we_p

  // Standard DRAM operations
  val doRefresh   = cs_p && ras_p && cas_p && !we_p
  val doActivate  = cs_p && ras_p && !cas_p && !we_p
  val doRead      = cs_p && !ras_p && cas_p && !we_p
  val doWrite     = cs_p && !ras_p && cas_p && we_p
  val doPrecharge = cs_p && ras_p && !cas_p && we_p

  // Extract row and column for timing checks only
  val reqRow = pending.addr(31, 32 - log2Ceil(params.numRows))
  val reqCol = pending.addr(log2Ceil(params.numCols) - 1, 0)

  // Without bank groups, always use short CCD
  val neededCCD = params.tCCD_S.U

  // Main FSM
  switch(state) {
    is(sIdle) {
      when(cmd.fire) {
        pending := cmd.bits
        state   := sProc
        printf(
          "[Bank %d, %d] Cycle %d: Accepted CMD cs=%d ras=%d cas=%d we=%d addr=0x%x data=0x%x lastColCycle=%d\n",
          rankIndex,
          bankIndex,
          cycleCounter,
          cmd.bits.cs,
          cmd.bits.ras,
          cmd.bits.cas,
          cmd.bits.we,
          cmd.bits.addr,
          cmd.bits.data,
          cmd.bits.lastColCycle
        )
      }
    }

    is(sProc) {
      // Self-refresh entry/exit
      when(doSrefEnter && !refreshInProg) {
        printf("[Bank %d, %d] Cycle %d: Received SREF_ENTER CMD\n", rankIndex, bankIndex, cycleCounter)
        state := sSrefEnter
      }.elsewhen(doSrefExit && !refreshInProg) {
        printf("[Bank %d, %d] Cycle %d: Received SREF_EXIT CMD\n", rankIndex, bankIndex, cycleCounter)
        state := sSrefExit
      }
      // Standard refresh
      .elsewhen(!refreshInProg && doRefresh) {
        refreshInProg := true.B
        refreshCntr   := params.tRFC.U
        printf("[Bank %d, %d] Cycle %d: BEGIN REFRESH - %d cycles\n", rankIndex, bankIndex, cycleCounter, params.tRFC.U)
      }
      // Precharge
      .elsewhen(doPrecharge && !refreshInProg &&
               elapsed(lastActivate, params.tRAS.U) && elapsed(lastPrecharge, params.tRP.U)) {
        rowActive     := false.B
        lastPrecharge := cycleCounter
        resp.valid    := true.B
        printf("[Bank %d, %d] Cycle %d: PRECHARGE issued\n", rankIndex, bankIndex, cycleCounter)
      }
      // Activate
      .elsewhen(doActivate && !refreshInProg) {
        val oldest = activateTimes(actPtr)
        when(elapsed(oldest, params.tFAW.U) && elapsed(lastActivate, params.tRRD_L.U)) {
          rowActive             := true.B
          activeRow             := reqRow
          lastActivate          := cycleCounter
          activateTimes(actPtr) := cycleCounter
          actPtr                := actPtr + 1.U
          resp.valid            := true.B
          printf("[Bank %d, %d] Cycle %d: ACTIVATE row=%d\n", rankIndex, bankIndex, cycleCounter, reqRow)
        }
      }
      // Read
      .elsewhen(doRead && rowActive && !refreshInProg &&
               elapsed(lastActivate, params.tRCDRD.U) && elapsed(pending.lastColCycle, neededCCD) &&
               elapsed(lastReadEnd, params.tCCD_L.U) && elapsed(lastWriteEnd, params.tWTR_L.U) &&
               elapsed(lastPrecharge, params.tRP.U)) {
        val data = mem.read(activeRow * params.numCols.U + reqCol)
        resp.bits.data := data
        resp.valid     := true.B
        lastReadEnd    := cycleCounter + params.CL.U
        printf("[Bank %d, %d] Cycle %d: READ row=%d col=%d data=0x%x\n", rankIndex, bankIndex, cycleCounter, activeRow, reqCol, data)
      }
      // Write scoped to specific column in current row
      .elsewhen(doWrite && rowActive && !refreshInProg &&
               elapsed(lastActivate, params.tRCDWR.U) && elapsed(pending.lastColCycle, neededCCD) &&
               elapsed(lastWriteEnd, params.tCCD_L.U) && elapsed(lastPrecharge, params.tRP.U)) {
        val writeIndex = activeRow * params.numCols.U + reqCol
        mem.write(writeIndex, pending.data)
        resp.bits.data := pending.data
        resp.valid     := true.B
        lastWriteEnd   := cycleCounter + params.CWL.U + params.tWR.U
        printf("[Bank %d, %d] Cycle %d: WRITE row=%d col=%d data=0x%x\n", rankIndex, bankIndex, cycleCounter, activeRow, reqCol, pending.data)
      }

      // Complete any in-progress refresh
      when(refreshInProg) {
        refreshCntr := refreshCntr - 1.U
        when(refreshCntr === 1.U) {
          refreshInProg := false.B
          lastRefresh   := cycleCounter
          rowActive     := false.B
          resp.valid    := true.B
          printf("[Bank %d, %d] Cycle %d: REFRESH complete\n", rankIndex, bankIndex, cycleCounter)
        }
      }

      // Return to Idle
      when(resp.fire) {
        printf("[Bank %d, %d] Cycle %d: Response fired\n", rankIndex, bankIndex, cycleCounter)
        state := sIdle
      }
    }

    is(sSrefEnter) {
      when(!refreshInProg) {
        refreshInProg := true.B
        refreshCntr   := params.tRFC.U
      }.elsewhen(refreshCntr === 0.U) {
        lastRefresh := cycleCounter
        printf("[Bank %d, %d] Cycle %d: ENTER SELF-REFRESH complete\n", rankIndex, bankIndex, cycleCounter)
        state       := sSref
      }.otherwise {
        refreshCntr := refreshCntr - 1.U
      }
    }

    is(sSref) {
      when(!refreshInProg) {
        refreshInProg := true.B
        refreshCntr   := params.tRFC.U
      }.otherwise {
        when(refreshCntr === 0.U) {
          lastRefresh   := cycleCounter
          refreshInProg := false.B
          printf("[Bank %d, %d] Cycle %d: AUTO REFRESH in SREF\n", rankIndex, bankIndex, cycleCounter)
        }.otherwise {
          refreshCntr := refreshCntr - 1.U
        }
      }
    }

    is(sSrefExit) {
      when(!doSrefExit) {
        printf("[Bank %d, %d] Cycle %d: EXIT SELF-REFRESH complete\n", rankIndex, bankIndex, cycleCounter)
        state := sIdle
      }
    }
  }

  io.activeSubMemories := Mux(state === sProc, 1.U, 0.U)

  // Optional performance tracking
  if (trackPerformance) {
    val perfTracker = Module(new BankPerformanceStatistics(localConfig))
    perfTracker.io.mem_request_fire  := io.memCmd.fire
    perfTracker.io.mem_request_bits  := io.memCmd.bits
    perfTracker.io.mem_response_fire := io.phyResp.fire
    perfTracker.io.mem_response_bits := io.phyResp.bits
    perfTracker.io.active_row       := reqRow
    perfTracker.io.active_col    := reqCol
  }
}
