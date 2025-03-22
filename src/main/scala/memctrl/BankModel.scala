package memctrl

import chisel3._
import chisel3.util._

// DRAMBank I/O definition remains similar.
class DRAMBankIO extends Bundle {
  // Inputs
  val cs    = Input(Bool())
  val ras   = Input(Bool())
  val cas   = Input(Bool())
  val we    = Input(Bool())
  val addr  = Input(UInt(32.W))
  val wdata = Input(UInt(32.W))
  
  // Outputs
  val response_complete = Output(Bool())
  val response_data     = Output(UInt(32.W))
}

// Extended parameters including row/column organization and additional delays.
case class DRAMBankParams(
  numRows: Int = 16,           // Number of rows
  numCols: Int = 64,           // Number of columns per row
  tWL_DELAY: Int = 3,          // Delay for wordline (row activation)
  tRCD_DELAY: Int = 5,         // Row-to-column delay (kept for compatibility)
  tCL_DELAY: Int = 5,          // CAS latency delay (read)
  tPRE_DELAY: Int = 10,        // Precharge delay (closing row)
  tREFRESH: Int = 10,          // Refresh delay (as before)
  tREFRESH_CYCLES: Int = 200   // Refresh cycle period
) {
  // Compute total addressable words.
  val addressSpaceSize: Int = numRows * numCols
}

class DRAMBank(params: DRAMBankParams = DRAMBankParams()) extends Module {
  val io = IO(new DRAMBankIO())

  // Internal state machine for DRAM command processing.
  object DRAMState extends ChiselEnum {
    val IDLE, ACTIVATE, READWRITE, PRECHARGE, REFRESH = Value
  }
  val state = RegInit(DRAMState.IDLE)

  // Timing parameters (delays in cycles)
  val tWL_DELAY   = params.tWL_DELAY.U  // Wordline activation delay
  val tRCD_DELAY  = params.tRCD_DELAY.U // Row-to-column delay (if needed)
  val tCL_DELAY   = params.tCL_DELAY.U  // CAS latency delay
  val tPRE_DELAY  = params.tPRE_DELAY.U // Precharge delay
  val tREFRESH    = params.tREFRESH.U   // Refresh delay
  val REFRESH_CYCLES = params.tREFRESH_CYCLES.U

  // Compute bit widths for row and column from parameters.
  val rowWidth = log2Ceil(params.numRows)
  val colWidth = log2Ceil(params.numCols)

  // Memory storage: a flat memory of words. The linear index is computed as:
  // index = activeRow * numCols + column.
  val memory = Mem(params.addressSpaceSize, UInt(32.W))
  
  // Registers
  val delay_counter = RegInit(0.U(32.W))
  val refresh_cycle_counter = RegInit(0.U(32.W))
  
  // New registers for row activation control.
  val rowActive = RegInit(false.B)
  val activeRow = RegInit(0.U(rowWidth.W))

  // Default outputs
  io.response_complete := false.B
  io.response_data     := 0.U

  // Memory corruption flag (simulate refresh effects)
  val memory_corrupted = RegInit(false.B)
  
  // Always update the refresh counter.
  refresh_cycle_counter := refresh_cycle_counter + 1.U
  when(refresh_cycle_counter === REFRESH_CYCLES) {
    refresh_cycle_counter := 0.U
    memory_corrupted := true.B  // Mark memory as corrupted
    rowActive := false.B        // Force row precharge
  }

  // Extract row and column from the incoming address.
  // Assume upper bits (starting at bit 31) are the row bits and lower bits are the column.
  // (You may need to adjust this split to match your addressing scheme.)
  val reqRow = io.addr(31, 32 - rowWidth)
  val reqCol = io.addr(colWidth - 1, 0)

  // Compute the flat memory index for a given row and column.
  def calcIndex(row: UInt, col: UInt): UInt = {
    row * params.numCols.U + col
  }

  // Default: maintain current delay.
  val next_delay = WireDefault(delay_counter)

  // DRAM Command Processing:
  // We now assume that:
  //   - Activate (open row): cs = 0, ras = 0, cas = 1, we = 1.
  //   - Read/Write: cs = 0, ras = 1, cas = 0. This command works only if the desired row is active.
  //   - Precharge (close row): cs = 0, ras = 0, cas = 1, we = 0.
  //   - Refresh: cs = 0, ras = 0, cas = 0, we = 1.
  when(io.cs === 1.U) {
    // Command Inhibit: reset delay, no action.
    next_delay := 0.U
    io.response_complete := false.B
    state := DRAMState.IDLE
  } .elsewhen(io.cs === 0.U && io.ras === 0.U && io.cas === 0.U && io.we === 1.U) {
    // Refresh operation.
    state := DRAMState.REFRESH
    when(delay_counter === 0.U) {
      next_delay := tREFRESH
      io.response_complete := false.B
      printf("Refreshing: Loaded tREFRESH (%d cycles)\n", tREFRESH)
    } .otherwise {
      next_delay := delay_counter - 1.U
      when(delay_counter === 1.U) {
        refresh_cycle_counter := 0.U
        rowActive := false.B
        io.response_complete := true.B
      } .otherwise {
        io.response_complete := false.B
      }
    }
  } .elsewhen(io.cs === 0.U && io.ras === 0.U && io.cas === 1.U && io.we === 1.U) {
    // Activate operation: open a row.
    state := DRAMState.ACTIVATE
    when(delay_counter === 0.U) {
      // Start wordline activation.
      next_delay := tWL_DELAY
      io.response_complete := false.B
      printf("Activating: Opening row %d with tWL_DELAY (%d cycles)\n", reqRow, tWL_DELAY)
    } .otherwise {
      next_delay := delay_counter - 1.U
      when(delay_counter === 1.U) {
        // Complete activation: mark the row as active.
        rowActive := true.B
        activeRow := reqRow
        io.response_complete := true.B
        printf("Completed activation. Active row set to %d\n", reqRow)
      } .otherwise {
        io.response_complete := false.B
      }
    }
  } .elsewhen(io.cs === 0.U && io.ras === 1.U && io.cas === 0.U) {
    // Read/Write operation. This branch is only valid if a row is active and matches the request.
    state := DRAMState.READWRITE
    when(rowActive === false.B) {
      // No active row – cannot complete read/write.
      next_delay := 0.U
      io.response_complete := false.B
      printf("Error: Attempt to read/write with no active row!\n")
    } .elsewhen(activeRow =/= reqRow) {
      // Row miss: the requested row is not open.
      next_delay := 0.U
      io.response_complete := false.B
      printf("Error: Requested row %d does not match active row %d\n", reqRow, activeRow)
    } .otherwise {
      // If a row hit, wait for CAS latency and then perform the operation.
      when(delay_counter === 0.U) {
        next_delay := tCL_DELAY
        io.response_complete := false.B
        printf("Read/Write: Using active row %d; Loaded tCL_DELAY (%d cycles)\n", activeRow, tCL_DELAY)
      } .otherwise {
        next_delay := delay_counter - 1.U
        when(delay_counter === 1.U) {
          // Compute flat memory index using active row and column.
          val memIndex = calcIndex(activeRow, reqCol)
          when(io.we === 1.U) {
            // Read operation.
            io.response_data := memory.read(memIndex)
            printf("[DRAM] Reading data %d from row %d, col %d (index %d)\n", memory.read(memIndex), activeRow, reqCol, memIndex)
          } .otherwise {
            // Write operation.
            memory.write(memIndex, io.wdata)
            io.response_data := io.wdata
            printf("[DRAM] Writing %d to row %d, col %d (index %d)\n", io.wdata, activeRow, reqCol, memIndex)
          }
          io.response_complete := true.B
        } .otherwise {
          io.response_complete := false.B
        }
      }
    }
  } .elsewhen(io.cs === 0.U && io.ras === 0.U && io.cas === 1.U && io.we === 0.U) {
    // Precharge operation: close the active row.
    state := DRAMState.PRECHARGE
    when(delay_counter === 0.U) {
      next_delay := tPRE_DELAY
      io.response_complete := false.B
      printf("Precharge: Closing active row with tPRE_DELAY (%d cycles)\n", tPRE_DELAY)
    } .otherwise {
      next_delay := delay_counter - 1.U
      when(delay_counter === 1.U) {
        rowActive := false.B
        io.response_complete := true.B
        printf("Precharge complete. Row closed.\n")
      } .otherwise {
        io.response_complete := false.B
      }
    }
  } .otherwise {
    next_delay := delay_counter
    io.response_complete := false.B
    state := DRAMState.IDLE
  }

  // Update the delay counter at each clock cycle.
  delay_counter := next_delay
}
