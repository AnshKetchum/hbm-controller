package memctrl

import chisel3._
import chisel3.util._

/** MemoryController – A Chisel implementation of the basic memory controller FSM.
  *
  * Parameters:
  * - tRCD: Row to column delay (used for READ_ISSUE and WRITE_ISSUE stages)
  * - tCL: Column access latency (used during READ_PENDING and WRITE_PENDING stages)
  * - tPRE: Precharge delay (used in the DONE stage)
  * - tREFRESH: Refresh delay
  * - REFRESH_CYCLE_COUNT: Total cycles before a refresh is needed
  * - COUNTER_SIZE: Bit-width for the delay counter registers
  * - IDLE_DELAY, READ_ISSUE_DELAY, WRITE_ISSUE_DELAY, READ_PENDING_DELAY, WRITE_PENDING_DELAY, DONE_DELAY, REFRESH_DELAY:
  *     Delay values for each stage.
  */
class MemoryController(
  val tRCD:            Int = 5,
  val tCL:             Int = 5,
  val tPRE:            Int = 10,
  val tREFRESH:        Int = 10,
  val REFRESH_CYCLE_COUNT: Int = 200,
  val COUNTER_SIZE:    Int = 32,
  val IDLE_DELAY:      Int = 0,
  val READ_ISSUE_DELAY:Int = 5,  // defaults to tRCD
  val WRITE_ISSUE_DELAY:Int = 5, // defaults to tRCD
  val READ_PENDING_DELAY:Int = 5, // defaults to tCL
  val WRITE_PENDING_DELAY:Int = 5,
  val DONE_DELAY:      Int = 10, // defaults to tPRE
  val REFRESH_DELAY:   Int = 10  // defaults to tREFRESH
) extends Module {
  val io = IO(new Bundle {
    // USER -> CTRLLR
    val wr_en        = Input(Bool())
    val rd_en        = Input(Bool())
    val addr         = Input(UInt(32.W))
    val wdata        = Input(UInt(32.W))
    val request_valid= Input(Bool())
    val request_rdy  = Output(Bool())

    // CTRLLR -> MEM
    val request_addr = Output(UInt(32.W))
    val request_data = Output(UInt(32.W))
    val cs           = Output(Bool())
    val ras          = Output(Bool())
    val cas          = Output(Bool())
    val we           = Output(Bool())

    // MEM -> CTRLLR
    val response_complete = Input(Bool())
    val response_data     = Input(UInt(32.W))

    // CTRLLR -> USER
    val data         = Output(UInt(32.W))
    val done         = Output(Bool())
    val ctrllerstate = Output(UInt(5.W))
  })

  // FSM States
  val sIdle :: sReadPending :: sReadIssue :: sWriteIssue :: sWritePending :: sDone :: sRefresh :: Nil = Enum(7)
  val state = RegInit(sIdle)

  // Delay counters
  val counter = RegInit(0.U(COUNTER_SIZE.W))
  val refreshDelayCounter = RegInit(0.U(COUNTER_SIZE.W))

  // Define combinational signals for the memory interface control lines.
  val csWire  = Wire(Bool())
  val rasWire = Wire(Bool())
  val casWire = Wire(Bool())
  val weWire  = Wire(Bool())

  // Default assignments (will be overwritten in switch)
  csWire  := false.B
  rasWire := false.B
  casWire := false.B
  weWire  := false.B

  // Output assignments for memory interface signals.
  io.cs  := csWire
  io.ras := rasWire
  io.cas := casWire
  io.we  := weWire

  // Determine if a new request should fire.
  val requestRdy = (state === sDone || state === sIdle)
  val requestFire = requestRdy && io.request_valid

  // Connect user interface signals.
  io.request_rdy  := requestRdy
  io.request_addr := io.addr
  io.request_data := io.wdata
  io.data         := io.response_data
  io.done         := (state === sDone)
  io.ctrllerstate := state.asUInt // output the raw state as a 5-bit value

  // FSM output control signals based on state.
  switch(state) {
    is(sIdle) {
      csWire  := true.B
      rasWire := false.B
      casWire := false.B
      weWire  := false.B
    }
    is(sReadIssue) {
      csWire  := false.B
      rasWire := false.B
      casWire := true.B
      weWire  := true.B
    }
    is(sWriteIssue) {
      csWire  := false.B
      rasWire := false.B
      casWire := true.B
      weWire  := true.B
    }
    is(sReadPending) {
      csWire  := false.B
      rasWire := true.B
      casWire := false.B
      weWire  := true.B
    }
    is(sWritePending) {
      csWire  := false.B
      rasWire := true.B
      casWire := false.B
      weWire  := false.B
    }
    is(sDone) {
      csWire  := false.B
      rasWire := false.B
      casWire := true.B
      weWire  := false.B
    }
    is(sRefresh) {
      csWire  := false.B
      rasWire := false.B
      casWire := false.B
      weWire  := true.B
    }
  }

  // Next state and counter value (combinational logic).
  val nextStateWire   = Wire(UInt(3.W))
  val nextCounterWire = Wire(UInt(COUNTER_SIZE.W))
  nextStateWire   := state // default assignment
  nextCounterWire := 0.U     // default assignment

  switch(state) {
    is(sIdle) {
      nextStateWire := sIdle // default remains
      when(refreshDelayCounter + tREFRESH.U >= REFRESH_CYCLE_COUNT.U) {
        nextStateWire   := sRefresh
        nextCounterWire := REFRESH_DELAY.U
      } .otherwise {
        when(io.rd_en && requestFire) {
          nextStateWire   := sReadIssue
          nextCounterWire := READ_ISSUE_DELAY.U
        } .elsewhen(io.wr_en && requestFire) {
          nextStateWire   := sWriteIssue
          nextCounterWire := WRITE_ISSUE_DELAY.U
        }
      }
    }
    is(sReadIssue) {
      nextCounterWire := READ_PENDING_DELAY.U
      nextStateWire   := sReadIssue // default remains
      when(io.response_complete || (counter === 0.U)) {
        nextStateWire := sReadPending
      }
    }
    is(sReadPending) {
      nextCounterWire := DONE_DELAY.U
      when(io.response_complete || (counter === 0.U)) {
        nextStateWire := sDone
      } .otherwise {
        nextStateWire := sReadPending
      }
    }
    is(sWriteIssue) {
      nextCounterWire := WRITE_PENDING_DELAY.U
      when(io.response_complete || (counter === 0.U)) {
        nextStateWire := sWritePending
      } .otherwise {
        nextStateWire := sWriteIssue
      }
    }
    is(sWritePending) {
      nextCounterWire := DONE_DELAY.U
      when(io.response_complete || (counter === 0.U)) {
        nextStateWire := sDone
      } .otherwise {
        nextStateWire := sWritePending
      }
    }
    is(sDone) {
      nextCounterWire := IDLE_DELAY.U
      when(io.response_complete || (counter === 0.U)) {
        nextStateWire := sIdle
      } .otherwise {
        nextStateWire := sDone
      }
    }
    is(sRefresh) {
      nextCounterWire := IDLE_DELAY.U
      when(io.response_complete || (counter === 0.U)) {
        nextStateWire := sIdle
      } .otherwise {
        nextStateWire := sRefresh
      }
    }
  }

  // Sequential (clocked) logic.
  when (reset.asBool) {
    state               := sIdle
    counter             := 0.U
    refreshDelayCounter := 0.U
  } .otherwise {
    // In simulation you might want to print when the transaction is DONE.
    // (For simulation prints, you can use Chisel’s printf if desired.)
    // Update counter and refreshDelayCounter based on state change.
    when(state =/= nextStateWire) {
      counter := nextCounterWire
      when(state === sRefresh) {
        refreshDelayCounter := 0.U
      } .otherwise {
        refreshDelayCounter := refreshDelayCounter + 1.U
      }
    } .otherwise {
      counter := counter - 1.U
      refreshDelayCounter := refreshDelayCounter + 1.U
    }
    state := nextStateWire
  }
}
