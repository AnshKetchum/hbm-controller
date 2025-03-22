package memctrl

import chisel3._
import chisel3.util._

class MemoryController(
  val tRCD:            Int = 5,
  val tCL:             Int = 5,
  val tPRE:            Int = 10,
  val tREFRESH:        Int = 10,
  val REFRESH_CYCLE_COUNT: Int = 200,
  val COUNTER_SIZE:    Int = 32,
  val IDLE_DELAY:      Int = 0,
  val READ_ISSUE_DELAY:Int = 5,
  val WRITE_ISSUE_DELAY:Int = 5,
  val READ_PENDING_DELAY:Int = 5,
  val WRITE_PENDING_DELAY:Int = 5,
  val PRECHARGE_DELAY:      Int = 10,
  val REFRESH_DELAY:   Int = 10
) extends Module {
  val io = IO(new Bundle {
    val wr_en        = Input(Bool())
    val rd_en        = Input(Bool())
    val addr         = Input(UInt(32.W))
    val wdata        = Input(UInt(32.W))
    val request_valid= Input(Bool())
    val request_rdy  = Output(Bool())

    val request_addr = Output(UInt(32.W))
    val request_data = Output(UInt(32.W))
    val cs           = Output(Bool())
    val ras          = Output(Bool())
    val cas          = Output(Bool())
    val we           = Output(Bool())

    val response_complete = Input(Bool())
    val response_data     = Input(UInt(32.W))

    val data         = Output(UInt(32.W))
    val done         = Output(Bool())
    val ctrllerstate = Output(UInt(5.W))
  })

  val sIdle :: sReadPending :: sReadIssue :: sWriteIssue :: sWritePending :: sPrecharge :: sDone :: sRefresh :: Nil = Enum(8)

  val state = RegInit(sIdle)

  val counter = RegInit(0.U(COUNTER_SIZE.W))
  val refreshDelayCounter = RegInit(0.U(COUNTER_SIZE.W))

  // New register to store latest response data
  val responseDataReg = RegInit(0.U(32.W))

  val csWire  = WireDefault(false.B)
  val rasWire = WireDefault(false.B)
  val casWire = WireDefault(false.B)
  val weWire  = WireDefault(false.B)

  io.cs  := csWire
  io.ras := rasWire
  io.cas := casWire
  io.we  := weWire

  val requestRdy = (state === sDone || state === sIdle)
  val requestFire = requestRdy && io.request_valid

  io.request_rdy  := requestRdy
  io.request_addr := io.addr
  io.request_data := io.wdata
  io.data         := responseDataReg // Output the stored response data
  io.done         := (state === sDone)
  io.ctrllerstate := state.asUInt

  switch(state) {
    is(sIdle) {
      csWire := true.B
    }
    is(sReadIssue) {
      casWire := true.B
      weWire  := true.B
    }
    is(sWriteIssue) {
      casWire := true.B
      weWire  := true.B
    }
    is(sReadPending) {
      rasWire := true.B
      weWire  := true.B
    }
    is(sWritePending) {
      rasWire := true.B
    }
    is(sPrecharge) {
      casWire := true.B
    }
    is(sDone) {
      csWire := true.B
    }
    is(sRefresh) {
      weWire := true.B
    }
  }

  val nextStateWire   = WireDefault(state)
  val nextCounterWire = WireDefault(0.U(COUNTER_SIZE.W))

  switch(state) {
    is(sIdle) {
      printf("[CTRLLR] CTRLLR is IDLING \n")
      when(refreshDelayCounter + tREFRESH.U >= REFRESH_CYCLE_COUNT.U) {
        nextStateWire := sRefresh
        nextCounterWire := REFRESH_DELAY.U
      }.elsewhen(io.rd_en && requestFire) {
        nextStateWire := sReadIssue
        nextCounterWire := READ_ISSUE_DELAY.U
      }.elsewhen(io.wr_en && requestFire) {
        nextStateWire := sWriteIssue
        nextCounterWire := WRITE_ISSUE_DELAY.U
      }
    }
    is(sReadIssue) {
      printf("[CTRLLR] CTRLLR is READ ISSUE-ing\n")
      nextCounterWire := READ_PENDING_DELAY.U
      when(io.response_complete || counter === 0.U) {
        nextStateWire := sReadPending
      }
    }
    is(sReadPending) {
      printf("[CTRLLR] CTRLLR is READ PENDING-ing\n")
      nextCounterWire := PRECHARGE_DELAY.U
      when(io.response_complete || counter === 0.U) {
        nextStateWire := sPrecharge
        responseDataReg := io.response_data
        printf("[CTRLLR] READING 0x%x \n", io.response_data)
      }
    }
    is(sWriteIssue) {
      printf("[CTRLLR] CTRLLR is WRITE ISSUE-ing\n")
      nextCounterWire := WRITE_PENDING_DELAY.U
      when(io.response_complete || counter === 0.U) {
        nextStateWire := sWritePending
      }
    }
    is(sWritePending) {
      printf("[CTRLLR] CTRLLR is WRITE PENDING-ing\n")
      nextCounterWire := PRECHARGE_DELAY.U
      when(io.response_complete || counter === 0.U) {
        nextStateWire := sPrecharge
        responseDataReg := io.wdata
        printf("[CTRLLR] WRITING 0x%x \n", io.wdata)
      }
    }
    is(sPrecharge) {
      printf("[CTRLLR] CTRLLR is PRECHARGE-ing\n")
      nextCounterWire := IDLE_DELAY.U
      when(io.response_complete || counter === 0.U) {
        // Store response data only if response is complete
        nextStateWire := sDone
      }
    }
    is(sDone) {
      nextCounterWire := IDLE_DELAY.U
      nextStateWire := sIdle
      printf("[CTRLLR] Response complete - %d %d.\n", io.response_complete, counter)
      printf("[CTRLLR] Stored response data - %d.\n", responseDataReg)
    }
    is(sRefresh) {
      nextCounterWire := IDLE_DELAY.U
      when(io.response_complete || counter === 0.U) {
        nextStateWire := sIdle
      }
    }
  }

  when(reset.asBool) {
    state := sIdle
    counter := 0.U
    refreshDelayCounter := 0.U
    responseDataReg := 0.U
  }.otherwise {
    when(state =/= nextStateWire) {
      counter := nextCounterWire
      refreshDelayCounter := Mux(state === sRefresh, 0.U, refreshDelayCounter + 1.U)
    }.otherwise {
      counter := counter - 1.U
      refreshDelayCounter := refreshDelayCounter + 1.U
    }
    state := nextStateWire
  }
}
