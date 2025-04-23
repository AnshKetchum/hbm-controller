package memctrl

import chisel3._
import chisel3.util._

class MemoryControllerFSM(params: DRAMBankParameters) extends Module {
  val io = IO(new Bundle {
    val req     = Flipped(Decoupled(new ControllerRequest))
    val resp    = Decoupled(new ControllerResponse)
    val cmdOut  = Decoupled(new PhysicalMemoryCommand)
    val phyResp = Flipped(Decoupled(new PhysicalMemoryResponse))
    val stateOut = Output(UInt(3.W))
    
  })

  // --------------------------------------------------
  val cycleCounter = RegInit(0.U(64.W))
  cycleCounter := cycleCounter + 1.U

  val lastActivate  = RegInit(0.U(64.W))
  val lastPrecharge = RegInit(0.U(64.W))
  val lastReadEnd   = RegInit(0.U(64.W))
  val lastWriteEnd  = RegInit(0.U(64.W))
  val lastRefresh   = RegInit(0.U(64.W))
  val activateTimes = Reg(Vec(4, UInt(64.W)))
  val actPtr        = RegInit(0.U(2.W))

  // capture the incoming request…
  val reqReg         = Reg(new ControllerRequest)
  val reqIsRead      = RegInit(false.B)
  val reqIsWrite     = RegInit(false.B)
  val reqAddrReg     = RegInit(0.U(32.W))  // newly added
  val reqWdataReg    = RegInit(0.U(32.W))  // newly added
  val requestActive  = RegInit(false.B)
  val issuedAddrReg  = RegInit(0.U(32.W))
  val responseDataReg = RegInit(0.U(32.W))

  // Single Read/Write states
  val sIdle :: sActivate :: sRead :: sWrite :: sPrecharge :: sDone :: sRefresh :: Nil = Enum(7)

  val state     = RegInit(sIdle)
  val counter   = RegInit(0.U(32.W))
  val sentCmd   = RegInit(false.B)
  val prevState = RegNext(state)
  when(prevState =/= state) { sentCmd := false.B }
  io.stateOut  := state

  // Request acceptance
  io.req.ready := (state === sIdle) && !requestActive
  when(state === sIdle && io.req.fire) {
    reqReg        := io.req.bits
    reqIsRead     := io.req.bits.rd_en
    reqIsWrite    := io.req.bits.wr_en
    reqAddrReg    := io.req.bits.addr       // latch address
    reqWdataReg   := io.req.bits.wdata     // latch write-data
    requestActive := true.B
  }

  // Command register defaults
  val cmdReg = Wire(new PhysicalMemoryCommand)
  cmdReg.addr := reqAddrReg                 // use latched addr
  cmdReg.data := reqWdataReg                // use latched wdata
  cmdReg.cs   := true.B
  cmdReg.ras  := false.B
  cmdReg.cas  := false.B
  cmdReg.we   := false.B
  io.cmdOut.bits := cmdReg

  val issuingCmdState = Seq(sActivate, sRead, sWrite, sPrecharge, sRefresh)
  io.cmdOut.valid := issuingCmdState.map(_ === state).reduce(_ || _) && !sentCmd && ~cmdReg.cs

  // Response register
  val respReg = Wire(new ControllerResponse)
  respReg.addr  := reqAddrReg             // use latched addr
  respReg.wr_en := reqIsWrite
  respReg.rd_en := reqIsRead
  respReg.wdata := reqWdataReg           // use latched write-data
  respReg.data  := responseDataReg
  io.resp.bits  := respReg
  io.resp.valid := (state === sDone)

  // Timing helpers
  def elapsed(since: UInt, d: UInt): Bool = (cycleCounter - since) >= d

  switch(state) {
    is(sIdle) {
      when(elapsed(lastRefresh, params.tREFI.U)) {
        state := sRefresh
      } .elsewhen(requestActive) {
        state := sActivate
      }
    }

    is(sActivate) {
      when(!sentCmd) {
        cmdReg.cs  := false.B
        cmdReg.ras := false.B
        cmdReg.cas := true.B
        cmdReg.we  := true.B
      }
      when(io.cmdOut.fire) { sentCmd := true.B }

      val canFAW  = elapsed(activateTimes(actPtr), params.tFAW.U)
      val canRRD  = elapsed(lastActivate, params.tRRD_L.U)
      val canRP   = elapsed(lastPrecharge, params.tRP.U)
      val canRFC  = elapsed(lastRefresh, params.tRFC.U)
      when((sentCmd && io.phyResp.fire)) {
        lastActivate          := cycleCounter
        activateTimes(actPtr) := cycleCounter
        actPtr                := actPtr + 1.U
        sentCmd               := false.B
        state                 := Mux(reqIsRead, sRead, sWrite)
        printf("\n [Controller] Activation complete. \n")
      }
    }

    is(sRead) {
      when(!sentCmd) {
        cmdReg.cs  := false.B
        cmdReg.ras := true.B
        cmdReg.cas := false.B
        cmdReg.we  := true.B
      }
      when(io.cmdOut.fire) {
        sentCmd       := true.B
        issuedAddrReg := reqAddrReg
        counter       := params.CL.U
      }

      when(sentCmd) {
        printf("Complete read ... %d %d %d %d %d\n", counter, io.phyResp.fire, io.phyResp.bits.addr, issuedAddrReg, io.phyResp.bits.data)
        when((io.phyResp.fire) && io.phyResp.bits.addr === issuedAddrReg) {
          responseDataReg := io.phyResp.bits.data
          printf("In READ here, receiving %d ... \n", io.phyResp.bits.data)
          lastReadEnd     := cycleCounter
          sentCmd         := false.B
          state           := sPrecharge
        } .otherwise {
          printf("In READ otherwise ... \n")
          counter := counter - 1.U
        }
      }
    }

    is(sWrite) {
      when(!sentCmd) {
        printf("[CONTROLLER] Initiating Write\n")
        cmdReg.cs  := false.B
        cmdReg.ras := true.B
        cmdReg.cas := false.B
        cmdReg.we  := false.B
      }
      when(io.cmdOut.fire) {
        sentCmd      := true.B
        lastWriteEnd := cycleCounter + params.CWL.U + params.tWR.U
      }

      when(sentCmd && (io.phyResp.fire)) {
        printf("[CONTROLLER] Received Write Ack\n")
        sentCmd          := false.B
        state            := sPrecharge
        responseDataReg := io.phyResp.bits.data
      }
    }

    is(sPrecharge) {
      val tRTP = Mux(reqIsRead, params.tRTP_L.U, params.tRTP_S.U)
      when(!sentCmd) {
        cmdReg.cs  := false.B
        cmdReg.ras := false.B
        cmdReg.cas := true.B
        cmdReg.we  := false.B
      }
      when(io.cmdOut.fire) { sentCmd := true.B }

      // printf("[Controller] In pre-charge %d %d\n", sentCmd, io.phyResp.fire)
      when(sentCmd && (io.phyResp.fire)) {
        printf("[Controller] In pre-charge, now moving to DONE\n")
        lastPrecharge := cycleCounter
        sentCmd       := false.B
        state         := sDone
      }
    }

    is(sDone) {
      printf("DONE resp rdy %d valid %d rd_en %d wr_en %d\n",
             io.resp.ready, io.resp.valid, reqIsRead, reqIsWrite)
      when(io.resp.fire) {
        requestActive := false.B
        state         := sIdle
      }
    }

    is(sRefresh) {
      when(!sentCmd) {
        cmdReg.cs  := false.B
        cmdReg.ras := false.B
        cmdReg.cas := false.B
        cmdReg.we  := true.B
      }
      when(io.cmdOut.fire) { sentCmd := true.B }

      when(sentCmd && io.phyResp.fire) {
        lastRefresh := cycleCounter
        sentCmd     := false.B
        state       := sIdle
      }
    }
  }

  when(state =/= sIdle) {
      // printf("State %d %d addr=%d wdata=%d mem data = %d\n", state, sDone, reqAddrReg, reqWdataReg, responseDataReg)
      // printf("External Memory Interface: rdy (controller) =%d valid (mem)=%d", io.phyResp.ready, io.phyResp.valid)
  }

  io.phyResp.ready := true.B
}
