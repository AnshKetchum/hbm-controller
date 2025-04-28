// File: src/main/scala/memctrl/BankModel.scala
package memctrl

import chisel3._
import chisel3.util._

/** Single HBM2 1T DRAM bank with FSM-based Decoupled processing **/
class DRAMBank(params: DRAMBankParameters, localConfig: LocalConfigurationParameters) extends PhysicalBankModuleBase {
  // Shorthand for IO
  val cmd  = io.memCmd    // Decoupled[BankMemoryCommand]
  val resp = io.phyResp   // Decoupled[BankMemoryResponse]

  // This bank’s fixed indices
  private val bankGroupId = localConfig.bankGroupIndex.U
  private val bankIndex   = localConfig.bankIndex.U

  // FSM states: Idle or Processing
  val sIdle :: sProc :: Nil = Enum(2)
  val state                = RegInit(sIdle)

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

  // Refresh in progress?
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
  cmd.ready      := state === sIdle && !refreshInProg
  resp.valid     := false.B
  resp.bits.addr := pending.addr
  resp.bits.data := pending.data

  // Accept new command
  when (state === sIdle && cmd.fire) {
    pending := cmd.bits
    state   := sProc
    printf("[Bank %d,%d] Cycle %d: Accepted CMD cs=%d ras=%d cas=%d we=%d addr=0x%x data=0x%x lastGrp=%d lastCyc=%d\n",
      bankGroupId, bankIndex, cycleCounter,
      cmd.bits.cs, cmd.bits.ras, cmd.bits.cas, cmd.bits.we,
      cmd.bits.addr, cmd.bits.data,
      cmd.bits.lastColBankGroup, cmd.bits.lastColCycle)
  } .elsewhen (state === sProc && resp.fire) {
    printf("[Bank %d,%d] Cycle %d: RESP fired addr=0x%x data=0x%x\n",
      bankGroupId, bankIndex, cycleCounter,
      resp.bits.addr, resp.bits.data)
    state := sIdle
  }

  // Decode command
  val cs_p  = !pending.cs
  val ras_p = !pending.ras
  val cas_p = !pending.cas
  val we_p  = !pending.we

  val doRefresh   = cs_p && ras_p && cas_p && !we_p
  val doActivate  = cs_p && ras_p && !cas_p && !we_p
  val doRead      = cs_p && !ras_p && cas_p && !we_p
  val doWrite     = cs_p && !ras_p && cas_p && we_p
  val doPrecharge = cs_p && ras_p && !cas_p && we_p

  val reqRow = pending.addr(31, 32 - log2Ceil(params.numRows))
  val reqCol = pending.addr(log2Ceil(params.numCols) - 1, 0)

  // tCCD selection based on previous bank-group from command metadata
  val neededCCD = Mux(
    pending.lastColBankGroup === bankGroupId,
    params.tCCD_L.U,
    params.tCCD_S.U
  )

  // Main FSM enforcing all timing
  when (state === sProc) {
    // 1) REFRESH command
    when (!refreshInProg && doRefresh) {
      refreshInProg := true.B
      refreshCntr   := params.tRFC.U
      printf("[Bank %d,%d] Cycle %d: BEGIN REFRESH\n", bankGroupId, bankIndex, cycleCounter)

    // 2) PRECHARGE command
    } .elsewhen (doPrecharge && !refreshInProg &&
                 elapsed(lastActivate, params.tRAS.U) &&
                 elapsed(lastPrecharge, params.tRP.U)) {
      rowActive     := false.B
      lastPrecharge := cycleCounter
      resp.valid    := true.B
      printf("[Bank %d,%d] Cycle %d: PRECHARGE issued\n", bankGroupId, bankIndex, cycleCounter)

    // 3) ACTIVATE command
    } .elsewhen (doActivate && !refreshInProg) {
      val oldest = activateTimes(actPtr)
      when (elapsed(oldest, params.tFAW.U) &&
            elapsed(lastActivate, params.tRRD_L.U)) {
        rowActive             := true.B
        activeRow             := reqRow
        lastActivate          := cycleCounter
        activateTimes(actPtr) := cycleCounter
        actPtr                := actPtr + 1.U
        resp.valid            := true.B
        printf("[Bank %d,%d] Cycle %d: ACTIVATE row=%d\n", bankGroupId, bankIndex, cycleCounter, reqRow)
      }

    // 4) READ command
    } .elsewhen (doRead && rowActive && !refreshInProg) {
      when (
        elapsed(lastActivate, params.tRCDRD.U)    && // ACT→READ
        elapsed(pending.lastColCycle, neededCCD)  && // tCCD_S/L
        elapsed(lastReadEnd, params.tCCD_L.U)     && // read→read gap
        elapsed(lastWriteEnd, params.tWTR_L.U)    && // write→read turn-around
        elapsed(lastPrecharge, params.tRP.U)         // no pending precharge
      ) {
        val data = mem.read(activeRow * params.numCols.U + reqCol)
        resp.bits.data := data
        resp.valid     := true.B
        lastReadEnd    := cycleCounter + params.CL.U
        printf("[Bank %d,%d] Cycle %d: READ  row=%d col=%d data=0x%x\n",
          bankGroupId, bankIndex, cycleCounter, activeRow, reqCol, data)
      }

    // 5) WRITE command
    } .elsewhen (doWrite && rowActive && !refreshInProg) {
      when (
        elapsed(lastActivate, params.tRCDWR.U)     && // ACT→WRITE
        elapsed(pending.lastColCycle, neededCCD)  && // tCCD_S/L
        elapsed(lastWriteEnd, params.tCCD_L.U)    && // write→write gap
        elapsed(lastPrecharge, params.tRP.U)         // no pending precharge
      ) {
        mem.write(activeRow * params.numCols.U + reqCol, pending.data)
        resp.bits.data := pending.data
        resp.valid     := true.B
        lastWriteEnd   := cycleCounter + params.CWL.U + params.tWR.U
        printf("[Bank %d,%d] Cycle %d: WRITE row=%d col=%d data=0x%x\n",
          bankGroupId, bankIndex, cycleCounter, activeRow, reqCol, pending.data)
      }
    }

    // 6) Complete REFRESH
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
  }

  // Active sub-memory count
  io.activeSubMemories := Mux(state === sProc, 1.U, 0.U)
}
