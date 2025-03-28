package memctrl

import chisel3._
import chisel3.util._

// DRAMBank I/O definition remains similar.
class DRAMBankIO extends Bundle {
  // Inputs (active low for cs, ras, cas)
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
  numRows:            Int = 16,   // Number of rows per bank
  numCols:            Int = 64,   // Number of columns per row
  tWL:                Int = 3,    // Wordline activation delay
  tRCD:               Int = 5,    // Row-to-column delay (if needed)
  tCL:                Int = 5,    // CAS latency delay (read)
  tPRE:               Int = 10,   // Precharge delay (closing row)
  tREFRESH:           Int = 10,   // Refresh delay
  refreshCycleCount:  Int = 200,  // Refresh cycle period
  counterSize:        Int = 32,   // Counter size for timing purposes
  idleDelay:          Int = 0,    // Idle delay for FSM
  readIssueDelay:     Int = 5,    // Delay for issuing a read command
  writeIssueDelay:    Int = 5,    // Delay for issuing a write command
  readPendingDelay:   Int = 5,    // Delay for pending read operations
  writePendingDelay:  Int = 5,    // Delay for pending write operations
  prechargeDelay:     Int = 10,   // Precharge delay specific to FSM
  refreshDelay:       Int = 10    // Additional refresh delay specific to FSM
) {
  // Total addressable words per bank.
  val addressSpaceSize: Int = numRows * numCols
}

class DRAMBank(params: DRAMBankParams = DRAMBankParams()) extends Module {
  val io = IO(new DRAMBankIO())

  // Active low: A command is issued when cs === 0.U.
  // When cs === 1.U, no command is active.
  // Similarly, other signals (ras, cas) follow the protocol:
  //   Refresh  : cs=0, ras=0, cas=0, we=1
  //   Activate : cs=0, ras=0, cas=1, we=1
  //   Read/Write: cs=0, ras=1, cas=0, (we distinguishes read/write)
  //   Precharge: cs=0, ras=0, cas=1, we=0

  // Define DRAM state machine.
  object DRAMState extends ChiselEnum {
    val IDLE, ACTIVATE, READWRITE, PRECHARGE, REFRESH = Value
  }
  val state = RegInit(DRAMState.IDLE)

  // Timing parameters (delays in cycles)
  val tWL   = params.tWL.U
  val tCL   = params.tCL.U
  val tPRE  = params.tPRE.U
  val tREFRESH = params.tREFRESH.U
  val REFRESH_CYCLES = params.refreshCycleCount.U

  // Compute bit widths for row and column from parameters.
  val rowWidth = log2Ceil(params.numRows)
  val colWidth = log2Ceil(params.numCols)

  // Memory storage: a flat memory of words.
  val memory = Mem(params.addressSpaceSize, UInt(32.W))
  
  // Delay and refresh counters.
  val delay_counter = RegInit(0.U(32.W))
  val refresh_cycle_counter = RegInit(0.U(32.W))
  
  // Row activation control.
  val rowActive = RegInit(false.B)
  val activeRow = RegInit(0.U(rowWidth.W))

  // Default outputs.
  io.response_complete := false.B
  io.response_data     := 0.U

  // A flag to simulate refresh effects.
  val memory_corrupted = RegInit(false.B)
  
  // Always update the refresh counter.
  refresh_cycle_counter := refresh_cycle_counter + 1.U
  when(refresh_cycle_counter === REFRESH_CYCLES) {
    refresh_cycle_counter := 0.U
    memory_corrupted := true.B  // Mark memory as corrupted
    rowActive := false.B        // Force precharge if needed
  }

  // Extract row and column from the incoming address.
  // Adjust the bit slicing as appropriate for your addressing scheme.
  val reqRow = io.addr(31, 32 - rowWidth)
  val reqCol = io.addr(colWidth - 1, 0)

  // Compute the flat memory index for a given row and column.
  def calcIndex(row: UInt, col: UInt): UInt = {
    row * params.numCols.U + col
  }

  // Default next delay is simply to hold the current delay.
  val next_delay = WireDefault(delay_counter)
  // Default next state is the current state.
  val next_state = WireDefault(state)

  // By default, no response is complete.
  io.response_complete := false.B

  // Process commands only when cs is low (active).
  when(io.cs === 1.U) {
    // Command Inhibit. Stay in IDLE.
    next_state := DRAMState.IDLE
    // No delay required.
  } .elsewhen(io.cs === 0.U) {
    // Refresh command: cs=0, ras=0, cas=0, we=1.
    when(io.ras === 0.U && io.cas === 0.U && io.we === 1.U) {
      next_state := DRAMState.REFRESH
      when(delay_counter === 0.U) {
        // Start refresh delay.
        next_delay := tREFRESH
      } .otherwise {
        next_delay := delay_counter - 1.U
        when(delay_counter === 1.U) {
          // Refresh complete.
          refresh_cycle_counter := 0.U
          rowActive := false.B
          io.response_complete := true.B
        }
      }
    }
    // Activate command: cs=0, ras=0, cas=1, we=1.
    .elsewhen(io.ras === 0.U && io.cas === 1.U && io.we === 1.U) {
      next_state := DRAMState.ACTIVATE
      when(delay_counter === 0.U) {
        next_delay := tWL
      } .otherwise {
        next_delay := delay_counter - 1.U
        when(delay_counter === 1.U) {
          // Activation complete.
          rowActive := true.B
          activeRow := reqRow
          io.response_complete := true.B
        }
      }
    }
    // Read/Write command: cs=0, ras=1, cas=0.
    .elsewhen(io.ras === 1.U && io.cas === 0.U) {
      next_state := DRAMState.READWRITE
      // Check for a valid row.
      when(!rowActive) {
        next_delay := 0.U
      } .elsewhen(activeRow =/= reqRow) {
        // Mismatched row: cannot perform op.
        next_delay := 0.U
      } .otherwise {
        when(delay_counter === 0.U) {
          // Load CAS latency.
          next_delay := tCL
        } .otherwise {
          next_delay := delay_counter - 1.U
          when(delay_counter === 1.U) {
            val memIndex = calcIndex(activeRow, reqCol)
            // Distinguish read from write: when we === 1, treat as read.
            when(io.we === 1.U) {
              // Read operation.
              io.response_data := memory.read(memIndex)
            } .otherwise {
              // Write operation.
              memory.write(memIndex, io.wdata)
              io.response_data := io.wdata
              printf("[DRAM] Writing %d to row %d, col %d (index %d), addr - %d\n",
                     io.wdata, activeRow, reqCol, memIndex, io.addr)
            }
            io.response_complete := true.B
          }
        }
      }
    }
    // Precharge command: cs=0, ras=0, cas=1, we=0.
    .elsewhen(io.ras === 0.U && io.cas === 1.U && io.we === 0.U) {
      next_state := DRAMState.PRECHARGE
      when(delay_counter === 0.U) {
        next_delay := tPRE
      } .otherwise {
        next_delay := delay_counter - 1.U
        when(delay_counter === 1.U) {
          rowActive := false.B
          io.response_complete := true.B
        }
      }
    }
    // If none of the recognized command patterns match, remain in the current state.
    .otherwise {
      next_state := state
    }
  }

  // Update state and delay counter at every clock cycle.
  delay_counter := next_delay
  state         := next_state
}
