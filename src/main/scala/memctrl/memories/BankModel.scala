package memctrl

import chisel3._
import chisel3.util._
import chisel3.util.log2Ceil

/** Single HBM2 1T DRAM bank with FSM-based Decoupled processing plus command-driven self-refresh */
class DRAMBank(
    params: DRAMBankParameters,
    localConfig: LocalConfigurationParameters,
    trackPerformance: Boolean = false
) extends PhysicalBankModuleBase {
  // Shorthand for IO
  val cmd  = io.memCmd    // Decoupled[BankMemoryCommand]
  val resp = io.phyResp   // Decoupled[BankMemoryResponse]

  // This bank’s fixed indices
  private val bankGroupId = localConfig.bankGroupIndex.U
  private val bankIndex   = localConfig.bankIndex.U

  // FSM states: Idle, Processing, SREF_ENTER, SREF, SREF_EXIT
  val sIdle :: sProc :: sSrefEnter :: sSref :: sSrefExit :: Nil = Enum(5)
  val state = RegInit(sIdle)

  // Latch the incoming command
  val pending = Reg(new BankMemoryCommand)

  // 64-bit cycle counter
  val cycleCounter = RegInit(0.U(64.W))
  cycleCounter    := cycleCounter + 1.U

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

    // Row-buffer state
  val rowActive = RegInit(false.B)
  val activeRow = RegInit(0.U(log2Ceil(params.numRows).W))
  

  // Underlying memory array
  val mem = Mem(params.addressSpaceSize, UInt(32.W))

  // Helper: has `delay` cycles elapsed since `start`?
  private def elapsed(start: UInt, delay: UInt): Bool =
    (cycleCounter - start) >= delay

  // Default ready/valid
  cmd.ready      := (state === sIdle) && !refreshInProg && (state =/= sSref)
  resp.valid     := false.B
  resp.bits.request_id := pending.request_id
  resp.bits.addr := pending.addr
  resp.bits.data := pending.data

  // Decode command type from pending bits
  val cs_p  = !pending.cs
  val ras_p = !pending.ras
  val cas_p = !pending.cas
  val we_p  = !pending.we

  // New self-refresh entry/exit decode (from incoming pending)
  // SREF_ENTER CMD: CS=0, RAS=0, CAS=0, WE=0
  val doSrefEnter = cs_p && ras_p && cas_p && we_p
  // SREF_EXIT CMD: CS=0, RAS=1, CAS=1, WE=1
  val doSrefExit  = cs_p && !ras_p && !cas_p && !we_p

  // Standard DRAM operation decodes
  val doRefresh   = cs_p && ras_p && cas_p && !we_p
  val doActivate  = cs_p && ras_p && !cas_p && !we_p
  val doRead      = cs_p && !ras_p && cas_p && !we_p
  val doWrite     = cs_p && !ras_p && cas_p && we_p
  val doPrecharge = cs_p && ras_p && !cas_p && we_p

  val reqRow = pending.addr(31, 32 - log2Ceil(params.numRows))
  val reqCol = pending.addr(log2Ceil(params.numCols) - 1, 0)

  val neededCCD = Mux(
    pending.lastColBankGroup === bankGroupId,
    params.tCCD_L.U,
    params.tCCD_S.U
  )

  // Main FSM including command-based SREF
  switch (state) {
    is (sIdle) {
      when (cmd.fire) {
        pending := cmd.bits
        state   := sProc
        printf("[Bank %d,%d] Cycle %d: Accepted CMD cs=%d ras=%d cas=%d we=%d addr=0x%x data=0x%x lastGrp=%d lastCyc=%d\n",
          bankGroupId, bankIndex, cycleCounter,
          cmd.bits.cs, cmd.bits.ras, cmd.bits.cas, cmd.bits.we,
          cmd.bits.addr, cmd.bits.data,
          cmd.bits.lastColBankGroup, cmd.bits.lastColCycle)
      }
    }

    is (sProc) {
      // 1) Self-refresh entry request
      when (doSrefEnter && !refreshInProg) {
        printf("[Bank %d,%d] Cycle %d: Received SREF_ENTER CMD\n", bankGroupId, bankIndex, cycleCounter)
        state := sSrefEnter
      }
      // 2) Self-refresh exit request
      .elsewhen (doSrefExit && !refreshInProg) {
        printf("[Bank %d,%d] Cycle %d: Received SREF_EXIT CMD\n", bankGroupId, bankIndex, cycleCounter)
        state := sSrefExit
      }
      // 3) Standard refresh
      .elsewhen (!refreshInProg && doRefresh) {
        refreshInProg := true.B
        refreshCntr   := params.tRFC.U
        printf("[Bank %d,%d] Cycle %d: BEGIN REFRESH - %d cycles\n", bankGroupId, bankIndex, cycleCounter, params.tRFC.U)
      }
      // 4) Precharge
      .elsewhen (doPrecharge && !refreshInProg && elapsed(lastActivate, params.tRAS.U) && elapsed(lastPrecharge, params.tRP.U)) {
        rowActive     := false.B
        lastPrecharge := cycleCounter
        resp.valid    := true.B
        printf("[Bank %d,%d] Cycle %d: PRECHARGE issued\n", bankGroupId, bankIndex, cycleCounter)
      }
      // 5) Activate
      .elsewhen (doActivate && !refreshInProg) {
        val oldest = activateTimes(actPtr)
        when (elapsed(oldest, params.tFAW.U) && elapsed(lastActivate, params.tRRD_L.U)) {
          rowActive             := true.B
          activeRow             := reqRow
          lastActivate          := cycleCounter
          activateTimes(actPtr) := cycleCounter
          actPtr                := actPtr + 1.U
          resp.valid            := true.B
          printf("[Bank %d,%d] Cycle %d: ACTIVATE row=%d\n", bankGroupId, bankIndex, cycleCounter, reqRow)
        }
      }
      // 6) Read
      .elsewhen (doRead && rowActive && !refreshInProg &&
                 elapsed(lastActivate, params.tRCDRD.U) && elapsed(pending.lastColCycle, neededCCD) &&
                 elapsed(lastReadEnd, params.tCCD_L.U) && elapsed(lastWriteEnd, params.tWTR_L.U) && elapsed(lastPrecharge, params.tRP.U)) {
        val data = mem.read(activeRow * params.numCols.U + reqCol)
        resp.bits.data := data
        resp.valid     := true.B
        lastReadEnd    := cycleCounter + params.CL.U
        printf("[Bank %d,%d] Cycle %d: READ  row=%d col=%d data=0x%x\n",
          bankGroupId, bankIndex, cycleCounter, activeRow, reqCol, data)
      }
      // 7) Write
      .elsewhen (doWrite && rowActive && !refreshInProg &&
                 elapsed(lastActivate, params.tRCDWR.U) && elapsed(pending.lastColCycle, neededCCD) &&
                 elapsed(lastWriteEnd, params.tCCD_L.U) && elapsed(lastPrecharge, params.tRP.U)) {
        mem.write(activeRow * params.numCols.U + reqCol, pending.data)
        resp.bits.data := pending.data
        resp.valid     := true.B
        lastWriteEnd   := cycleCounter + params.CWL.U + params.tWR.U
        printf("[Bank %d,%d] Cycle %d: WRITE row=%d col=%d data=0x%x\n",
          bankGroupId, bankIndex, cycleCounter, activeRow, reqCol, pending.data)
      }
      // Complete standard refresh
      when (refreshInProg) {
        refreshCntr := refreshCntr - 1.U
        when (refreshCntr === 1.U) {
          refreshInProg := false.B
          lastRefresh   := cycleCounter
          rowActive     := false.B
          resp.valid    := true.B
          printf("[Bank %d,%d] Cycle %d: REFRESH complete\n", bankGroupId, bankIndex, cycleCounter)
        }
      }
      // Return to Idle once response fired
      when (resp.fire) {
        state := sIdle
      }
    }

    // Issue self-refresh entry internal sequence
    is (sSrefEnter) {
      // issue internal refresh cycles
      when (!refreshInProg) {
        refreshInProg := true.B
        refreshCntr   := params.tRFC.U
      } .elsewhen (refreshCntr === 0.U) {
        lastRefresh := cycleCounter
        printf("[Bank %d,%d] Cycle %d: ENTER SELF-REFRESH complete\n", bankGroupId, bankIndex, cycleCounter)
        state := sSref
      } .otherwise {
        refreshCntr := refreshCntr - 1.U
      }
    }

    // Self-refresh maintenance
    is (sSref) {
      // loop auto-refresh
      when (!refreshInProg) {
        refreshInProg := true.B
        refreshCntr   := params.tRFC.U
      } .otherwise {
        when (refreshCntr === 0.U) {
          lastRefresh := cycleCounter
          refreshInProg := false.B
          printf("[Bank %d,%d] Cycle %d: AUTO REFRESH in SREF\n", bankGroupId, bankIndex, cycleCounter)
        } .otherwise {
          refreshCntr := refreshCntr - 1.U
        }
      }
      // do not accept new commands until exit CMD
    }

    // Exit self-refresh on command
    is (sSrefExit) {
      // waiting for exit CMD in pending
      when (!doSrefExit) {
        printf("[Bank %d,%d] Cycle %d: EXIT SELF-REFRESH complete\n", bankGroupId, bankIndex, cycleCounter)
        state := sIdle
      }
    }
  }

  io.activeSubMemories := Mux(state === sProc, 1.U, 0.U)

  if (trackPerformance) {
    val perfTracker = Module(new BankPerformanceStatistics(localConfig))
    perfTracker.io.mem_request_fire := io.memCmd.fire
    perfTracker.io.mem_request_bits := io.memCmd.bits
    perfTracker.io.mem_response_fire:= io.phyResp.fire
    perfTracker.io.mem_response_bits:= io.phyResp.bits
  }
}
