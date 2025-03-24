package memctrl

import chisel3._
import chisel3.util._

/** User Request interface **/
class MemRequest extends Bundle {
  val rd_en = Bool()
  val wr_en = Bool()
  val addr  = UInt(32.W)
  val wdata = UInt(32.W)
}

/** User Response interface **/
class MemResponse extends Bundle {
  val data         = UInt(32.W)
  val done         = Bool()
  val ctrllerstate = UInt(5.W)
}

/** Memory Command interface (to external memory) **/
class MemCmd extends Bundle {
  val addr = UInt(32.W)
  val data = UInt(32.W)
  val cs   = Bool()
  val ras  = Bool()
  val cas  = Bool()
  val we   = Bool()
}

class MemoryController(
  val tRCD:              Int = 5,
  val tCL:               Int = 5,
  val tPRE:              Int = 10,
  val tREFRESH:          Int = 10,
  val REFRESH_CYCLE_COUNT:Int = 200,
  val COUNTER_SIZE:      Int = 32,
  val IDLE_DELAY:        Int = 0,
  val READ_ISSUE_DELAY:  Int = 5,
  val WRITE_ISSUE_DELAY: Int = 5,
  val READ_PENDING_DELAY:Int = 5,
  val WRITE_PENDING_DELAY:Int = 5,
  val PRECHARGE_DELAY:   Int = 10,
  val REFRESH_DELAY:     Int = 10
) extends Module {
  val io = IO(new Bundle {
    // User-facing request and response interfaces using Decoupled
    val in  = Flipped(Decoupled(new MemRequest))
    val out = Decoupled(new MemResponse)

    // Memory command interface (to external DRAM) as a Decoupled output
    val memCmd = Output(new MemCmd)

    // Memory response interface (from external DRAM)
    val memResp_valid = Input(Bool())
    val memResp_data  = Input(UInt(32.W))
  })

  //----------------------------------------------------------------------------
  // Internal Queues for Request and Response
  //----------------------------------------------------------------------------
  // Request Queue: user can always enqueue memory requests.
  val reqQueue = Module(new Queue(new MemRequest, entries = 8))
  reqQueue.io.enq <> io.in

  // Response Queue: enqueues response data when the operation is done.
  val respQueue = Module(new Queue(new MemResponse, entries = 8))
  io.out <> respQueue.io.deq

  //----------------------------------------------------------------------------
  // State Machine Setup
  //----------------------------------------------------------------------------
  val sIdle :: sReadIssue :: sReadPending :: sWriteIssue :: sWritePending :: sPrecharge :: sDone :: sRefresh :: Nil = Enum(8)

  val state = RegInit(sIdle)
  val counter = RegInit(0.U(COUNTER_SIZE.W))
  val refreshDelayCounter = RegInit(0.U(COUNTER_SIZE.W))
  val responseDataReg = RegInit(0.U(32.W))

  // Register to hold the current request (dequeued from reqQueue)
  val reqReg = Reg(new MemRequest)
  // Indicates whether a request is active (latched) for processing.
  val requestActive = RegInit(false.B)

  //----------------------------------------------------------------------------
  // Memory Command Signals
  //----------------------------------------------------------------------------
  // Default memory command signals.
  val memCmdReg = Wire(new MemCmd)
  memCmdReg.addr := reqReg.addr

  when (reqReg.wr_en) {
    memCmdReg.data := reqReg.wdata
  }.otherwise {
    memCmdReg.data := 0.U
  }
  
  memCmdReg.cs   := false.B
  memCmdReg.ras  := false.B
  memCmdReg.cas  := false.B
  memCmdReg.we   := false.B

  // Connect to output. In this example, memCmd.valid is high when not idle.
  io.memCmd := memCmdReg

  //----------------------------------------------------------------------------
  // Response Generation
  //----------------------------------------------------------------------------
  // Build the response package
  val responseReg = Wire(new MemResponse)
  responseReg.data         := responseDataReg
  responseReg.done         := (state === sDone)
  responseReg.ctrllerstate := state.asUInt

  // Enqueue response when in DONE state.
  // (It will be accepted by the response queue when io.out.ready is true.)
  respQueue.io.enq.bits  := responseReg
  respQueue.io.enq.valid := (state === sDone)

  //----------------------------------------------------------------------------
  // Request Handling: Dequeue a new request in IDLE
  //----------------------------------------------------------------------------
  // When idle and no active request is held, try to dequeue one.
  when (state === sIdle && !requestActive && reqQueue.io.deq.valid) {
    reqReg := reqQueue.io.deq.bits
    requestActive := true.B
  }
  // Indicate ready to dequeue when idle.
  reqQueue.io.deq.ready := (state === sIdle) && !requestActive

  //----------------------------------------------------------------------------
  // Default next state and counter signals
  //----------------------------------------------------------------------------
  val nextStateWire   = WireDefault(state)
  val nextCounterWire = WireDefault(0.U(COUNTER_SIZE.W))

  //----------------------------------------------------------------------------
  // Main State Machine
  //----------------------------------------------------------------------------
  switch(state) {
    is(sIdle) {
      printf("[CTRLLR] IDLE-ing\n")

      // Default: assert chip select
      memCmdReg.cs := true.B
      // Check for refresh condition first.
      when(refreshDelayCounter + tREFRESH.U >= REFRESH_CYCLE_COUNT.U) {
        nextStateWire   := sRefresh
        nextCounterWire := REFRESH_DELAY.U
      } .elsewhen(requestActive) {
        // A request is available. Choose based on type.
        when(reqReg.rd_en) {
          nextStateWire   := sReadIssue
          nextCounterWire := READ_ISSUE_DELAY.U
          memCmdReg.addr  := reqReg.addr
        } .elsewhen(reqReg.wr_en) {
          nextStateWire   := sWriteIssue
          nextCounterWire := WRITE_ISSUE_DELAY.U
          memCmdReg.addr  := reqReg.addr
          memCmdReg.data  := reqReg.wdata
        }
      }
    }
    is(sReadIssue) {
      printf("[CTRLLR] READ ISSUE-ing\n")
      memCmdReg.cas := true.B
      memCmdReg.we  := true.B
      nextCounterWire := READ_PENDING_DELAY.U
      when(io.memResp_valid || counter === 0.U) {
        nextStateWire := sReadPending
      }
    }
    is(sReadPending) {
      printf("[CTRLLR] READ PENDING-ing\n")
      memCmdReg.ras := true.B
      memCmdReg.we  := true.B
      nextCounterWire := PRECHARGE_DELAY.U
      when(io.memResp_valid || counter === 0.U) {
        nextStateWire   := sPrecharge
        responseDataReg := io.memResp_data
        printf("[CTRLLR] Reading 0x%x\n", io.memResp_data)
      }
    }
    is(sWriteIssue) {
      printf("[CTRLLR] WRITE ISSUE-ing\n")
      memCmdReg.cas := true.B
      memCmdReg.we  := true.B
      nextCounterWire := WRITE_PENDING_DELAY.U
      when(io.memResp_valid || counter === 0.U) {
        nextStateWire := sWritePending
      }
    }
    is(sWritePending) {
      printf("[CTRLLR] WRITE PENDING-ing\n")
      memCmdReg.ras := true.B
      nextCounterWire := PRECHARGE_DELAY.U
      when(io.memResp_valid || counter === 0.U) {
        nextStateWire   := sPrecharge
        responseDataReg := reqReg.wdata
        printf("[CTRLLR] Writing 0x%x\n", reqReg.wdata)
      }
    }
    is(sPrecharge) {
      printf("[CTRLLR] PRECHARGE-ing\n")
      memCmdReg.cas := true.B
      nextCounterWire := IDLE_DELAY.U
      when(io.memResp_valid || counter === 0.U) {
        nextStateWire := sDone
      }
    }
    is(sDone) {
      nextCounterWire := IDLE_DELAY.U
      // Stay in DONE until the response is successfully enqueued
      when (respQueue.io.enq.fire) {
        nextStateWire := sIdle
        requestActive := false.B // Clear active request so that a new one can be loaded.
        printf("[CTRLLR] Operation complete. State: %d\n", state)
      }
    }
    is(sRefresh) {
      memCmdReg.we := true.B
      nextCounterWire := IDLE_DELAY.U
      when(io.memResp_valid || counter === 0.U) {
        nextStateWire := sIdle
      }
    }
  }

  //----------------------------------------------------------------------------
  // State and Counter Update Logic
  //----------------------------------------------------------------------------
  when(reset.asBool) {
    state               := sIdle
    counter             := 0.U
    refreshDelayCounter := 0.U
    responseDataReg     := 0.U
    requestActive       := false.B
  } .otherwise {
    when(state =/= nextStateWire) {
      counter             := nextCounterWire
      refreshDelayCounter := Mux(state === sRefresh, 0.U, refreshDelayCounter + 1.U)
    } .otherwise {
      counter             := counter - 1.U
      refreshDelayCounter := refreshDelayCounter + 1.U
    }
    state := nextStateWire
  }
}
