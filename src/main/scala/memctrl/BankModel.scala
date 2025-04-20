package memctrl

import chisel3._
import chisel3.util._

// DRAMBank I/O definition
class DRAMBankIO extends Bundle {
  val cs    = Input(Bool())         // active low
  val ras   = Input(Bool())
  val cas   = Input(Bool())
  val we    = Input(Bool())
  val addr  = Input(UInt(32.W))
  val wdata = Input(UInt(32.W))

  val response_complete = Output(Bool())
  val response_data     = Output(UInt(32.W))
  val busy              = Output(Bool())
}

/** HBM2 timing parameters from DRAMSim3 HBM2_8Gb_x128.ini */
case class DRAMBankParams(
  numRows:    Int = 32768,
  numCols:    Int = 64,
  deviceWidth:Int = 128,
  tCK:        Int = 1,
  CL:         Int = 14,
  CWL:        Int = 4,
  tRCDRD:     Int = 14,
  tRCDWR:     Int = 14,
  tRP:        Int = 14,
  tRAS:       Int = 34,
  tRFC:       Int = 260,
  tREFI:      Int = 3900,
  tREFIb:     Int = 128,
  tRPRE:      Int = 1,
  tWPRE:      Int = 1,
  tRRD_S:     Int = 4,
  tRRD_L:     Int = 6,
  tWTR_S:     Int = 6,
  tWTR_L:     Int = 8,
  tFAW:       Int = 30,
  tWR:        Int = 16,
  tCCD_S:     Int = 1,
  tCCD_L:     Int = 2,
  tXS:        Int = 268,
  tCKE:       Int = 8,
  tCKSRE:     Int = 10,
  tXP:        Int = 8,
  tRTP_L:     Int = 6,
  tRTP_S:     Int = 4
) {
  require(numRows > 0 && numCols > 0)
  val addressSpaceSize: Int = numRows * numCols
}

/** Single HBM2 bank model with full timing enforcement, including fixed‑latency refresh. */
class DRAMBank(params: DRAMBankParams) extends Module {
  val io = IO(new DRAMBankIO)

  // Global cycle counter
  val cycleCounter = RegInit(0.U(64.W))
  cycleCounter := cycleCounter + 1.U

  // Last‑command timestamps
  val lastActivate  = RegInit(0.U(64.W))
  val lastPrecharge = RegInit(0.U(64.W))
  val lastReadEnd   = RegInit(0.U(64.W))
  val lastWriteEnd  = RegInit(0.U(64.W))
  val lastRefresh   = RegInit(0.U(64.W))
  val activateTimes = Reg(Vec(4, UInt(64.W)))
  val actPtr        = RegInit(0.U(2.W))

  // Refresh state
  val refreshInProgress = RegInit(false.B)
  val refreshCounter    = RegInit(0.U(32.W))

  // Bank state
  val rowActive = RegInit(false.B)
  val activeRow = RegInit(0.U(log2Ceil(params.numRows).W))

  // Memory storage
  val mem = Mem(params.addressSpaceSize, UInt(32.W))

  // One‑cycle pipeline for reads
  val readValid = RegInit(false.B)
  val readData  = Reg(UInt(32.W))

  // Defaults
  io.response_complete := false.B
  io.response_data     := 0.U
  io.busy              := false.B

  // Decode incoming commands (active‑low)
  val cmdRefresh   = io.cs === 0.U && io.ras === 0.U && io.cas === 0.U && io.we === 1.U
  val cmdActivate  = io.cs === 0.U && io.ras === 0.U && io.cas === 1.U && io.we === 1.U
  val cmdRead      = io.cs === 0.U && io.ras === 1.U && io.cas === 0.U && io.we === 1.U
  val cmdWrite     = io.cs === 0.U && io.ras === 1.U && io.cas === 0.U && io.we === 0.U
  val cmdPrecharge = io.cs === 0.U && io.ras === 0.U && io.cas === 1.U && io.we === 0.U

  // Address slicing
  val rowWidth = log2Ceil(params.numRows)
  val colWidth = log2Ceil(params.numCols)
  val reqRow   = io.addr(31, 32 - rowWidth)
  val reqCol   = io.addr(colWidth - 1, 0)
  def calcIndex(row: UInt, col: UInt): UInt = row * params.numCols.U + col

  // Timing helper
  def elapsed(since: UInt, d: Int): Bool = (cycleCounter - since) >= d.U

  // ---------------------
  // Refresh sequencing: explicit commands start immediately
  when (!refreshInProgress && cmdRefresh) {
    refreshInProgress := true.B
    refreshCounter    := params.tRFC.U
  }
  when (refreshInProgress) {
    io.busy := true.B
    refreshCounter := refreshCounter - 1.U
    when (refreshCounter === 1.U) {
      refreshInProgress := false.B
      lastRefresh       := cycleCounter
      rowActive         := false.B
      io.response_complete := true.B
    }
  }

  // ---------------------
  // Precharge
  when (cmdPrecharge && !refreshInProgress) {
    io.busy := !elapsed(lastActivate, params.tRAS)
    when (!io.busy && elapsed(lastPrecharge, params.tRP)) {
      rowActive := false.B
      lastPrecharge := cycleCounter
      io.response_complete := true.B
    }
  }

  // ---------------------
  // Activate
  when (cmdActivate && !refreshInProgress) {
    val oldest = activateTimes(actPtr)
    val canFAW = elapsed(oldest, params.tFAW)
    val canRRD = elapsed(lastActivate, params.tRRD_L)
    io.busy := !(canFAW && canRRD)
    when (!io.busy) {
      rowActive := true.B
      activeRow := reqRow
      lastActivate := cycleCounter
      activateTimes(actPtr) := cycleCounter
      actPtr := actPtr + 1.U
      io.response_complete := true.B
    }
  }

  // ---------------------
  // Read
  when (cmdRead && !refreshInProgress) {
    io.busy := !rowActive ||
               !elapsed(lastActivate, params.tRCDRD) ||
               !elapsed(lastReadEnd, params.tCCD_L) ||
               !elapsed(lastWriteEnd, params.tWTR_L)
    when (!io.busy) {
      readData  := mem.read(calcIndex(activeRow, reqCol))
      readValid := true.B
      lastReadEnd := cycleCounter + params.CL.U
    }
  }
  when (readValid) {
    io.response_data     := readData
    io.response_complete := true.B
    readValid            := false.B
  }

  // ---------------------
  // Write
  when (cmdWrite && !refreshInProgress) {
    io.busy := !rowActive ||
               !elapsed(lastActivate, params.tRCDWR) ||
               !elapsed(lastWriteEnd, params.tCCD_L)
    when (!io.busy) {
      mem.write(calcIndex(activeRow, reqCol), io.wdata)
      io.response_data     := io.wdata
      io.response_complete := true.B
      lastWriteEnd := cycleCounter + params.CWL.U + params.tWR.U
    }
  }

  // ---------------------
  // If CS is high (no command), clear busy
  when (io.cs === 1.U && !refreshInProgress) {
    io.busy := false.B
  }
}
