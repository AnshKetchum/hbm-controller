package memctrl

import chisel3._
import chisel3.util._
import chisel3.util.log2Ceil

/** DRAM bank FSM that defers timing to an external TimingEngine. Accepts a `waitCycles` input per command and enforces
  * it.
  */
class DRAMBankWithWait(
  params:           DRAMBankParameters,
  localConfig:      LocalConfigurationParameters,
  trackPerformance: Boolean = false)
    extends PhysicalBankModuleBase {

  // I/O
  val cmd        = io.memCmd  // Decoupled[BankMemoryCommand]
  val resp       = io.phyResp // Decoupled[BankMemoryResponse]
  val waitCycles = io.waitCycles

  // fixed indices
  private val rankIdx = localConfig.rankIndex.U
  private val bankIdx = localConfig.bankIndex.U

  // FSM states: Idle -> Wait -> Execute -> Resp
  val sIdle :: sWait :: sExec :: sResp :: Nil = Enum(4)
  val state                                   = RegInit(sIdle)

  // latch incoming command
  val pending = Reg(new BankMemoryCommand)

  // countdown register
  val timer = RegInit(0.U(32.W))

  // row buffer tracking
  val rowActive = RegInit(false.B)
  val activeRow = RegInit(0.U(log2Ceil(params.numRows).W))

  // underlying memory array
  val mem = Mem(params.addressSpaceSize, UInt(32.W))

  // decode bits from pending
  val cs_p  = !pending.cs
  val ras_p = !pending.ras
  val cas_p = !pending.cas
  val we_p  = !pending.we

  val doActivate  = cs_p && ras_p && !cas_p && !we_p
  val doRead      = cs_p && !ras_p && cas_p && !we_p
  val doWrite     = cs_p && !ras_p && cas_p && we_p
  val doPrecharge = cs_p && ras_p && !cas_p && we_p
  val doRefresh   = cs_p && ras_p && cas_p && !we_p
  val doSrefEnter = cs_p && ras_p && cas_p && we_p
  val doSrefExit  = cs_p && !ras_p && !cas_p && !we_p

  // address fields
  val reqRow = pending.addr(31, 32 - log2Ceil(params.numRows))
  val reqCol = pending.addr(log2Ceil(params.numCols) - 1, 0)

  // defaults
  cmd.ready            := (state === sIdle)
  resp.valid           := false.B
  resp.bits.request_id := pending.request_id
  resp.bits.addr       := pending.addr
  resp.bits.data       := pending.data

  switch(state) {
    is(sIdle) {
      when(cmd.fire) {
        printf("[Bank Model] Received command. time = %d \n", waitCycles)
        pending := cmd.bits
        timer   := waitCycles
        state   := sWait
      }
    }

    is(sWait) {
      // count down external wait
      when(timer === 0.U) {
        printf("[Bank Model] Timer hit zero.\n")
        state := sExec
      }.otherwise {
        timer := timer - 1.U
      }
    }

    is(sExec) {
      // perform the operation
      when(doActivate) {
        rowActive  := true.B
        activeRow  := reqRow
        resp.valid := true.B
      }.elsewhen(doRead) {
        val data = mem.read(activeRow * params.numCols.U + reqCol)
        resp.bits.data := data
        resp.valid     := true.B
      }.elsewhen(doWrite) {
        val idx = activeRow * params.numCols.U + reqCol
        mem.write(idx, pending.data)
        resp.bits.data := pending.data
        resp.valid     := true.B
      }.elsewhen(doPrecharge) {
        rowActive  := false.B
        resp.valid := true.B
      }.elsewhen(doRefresh) {
        rowActive  := false.B
        resp.valid := true.B
      }.elsewhen(doSrefEnter || doSrefExit) {
        resp.valid := true.B
      }

      when(resp.fire) {
        state := sResp
      }
    }

    is(sResp) {
      // ensure one cycle for response
      state := sIdle
    }
  }

  io.activeSubMemories := Mux(state === sExec, 1.U, 0.U)

  // optional performance tracking
  if (trackPerformance) {
    val perf = Module(new BankPerformanceStatistics(localConfig))
    perf.io.mem_request_fire  := io.memCmd.fire
    perf.io.mem_request_bits  := io.memCmd.bits
    perf.io.mem_response_fire := io.phyResp.fire
    perf.io.mem_response_bits := io.phyResp.bits
    perf.io.active_row        := reqRow
    perf.io.active_col        := reqCol
  }
}
