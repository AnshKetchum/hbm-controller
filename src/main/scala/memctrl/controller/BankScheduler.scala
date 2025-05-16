package memctrl

import chisel3._
import chisel3.util._
import chisel3.util.log2Ceil

class MemoryControllerFSM(
    params: DRAMBankParameters,
    localConfiguration: LocalConfigurationParameters,
    memoryConfig: MemoryConfigurationParameters,
    trackPerformance: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val req      = Flipped(Decoupled(new ControllerRequest))
    val resp     = Decoupled(new ControllerResponse)
    val cmdOut   = Decoupled(new PhysicalMemoryCommand)
    val phyResp  = Flipped(Decoupled(new PhysicalMemoryResponse))
    val stateOut = Output(UInt(3.W))
  })

  // --------------------------------------------------
  // Address decoder for response filtering
  val respDec = Module(new AddressDecoder(memoryConfig))
  respDec.io.addr := io.phyResp.bits.addr

  // --------------------------------------------------
  // Global cycle counter & timing regs
  val cycleCounter = RegInit(0.U(64.W))
  cycleCounter := cycleCounter + 1.U

  val lastActivate  = RegInit(0.U(64.W))
  val lastPrecharge = RegInit(0.U(64.W))
  val lastReadEnd   = RegInit(0.U(64.W))
  val lastWriteEnd  = RegInit(0.U(64.W))
  val lastRefresh   = RegInit(0.U(64.W))
  val selfRefreshThreshold = 1000.U
  val activateTimes = Reg(Vec(memoryConfig.numberOfBanks, UInt(64.W)))
  val actPtr        = RegInit(0.U(log2Ceil(memoryConfig.numberOfBanks).W))

  // --------------------------------------------------
  // Latch incoming request fields
  val reqReg          = Reg(new ControllerRequest)
  val reqIsRead       = RegInit(false.B)
  val reqIsWrite      = RegInit(false.B)
  val reqAddrReg      = RegInit(0.U(32.W))
  val reqWdataReg     = RegInit(0.U(32.W))
  val reqIDReg        = RegInit(0.U(32.W))
  val requestActive   = RegInit(false.B)
  val issuedAddrReg   = RegInit(0.U(32.W))
  val responseDataReg = RegInit(0.U(32.W))

  // --------------------------------------------------
  // FSM states
  val sIdle :: sActivate :: sRead :: sWrite :: sPrecharge :: sDone :: sRefresh :: sSrefEnter :: sSref :: sSrefExit :: Nil = Enum(10)
  val state     = RegInit(sIdle)
  val prevState = RegNext(state)
  val counter   = RegInit(0.U(32.W))
  val idleCounter = RegInit(0.U(32.W))
  val sentCmd   = RegInit(false.B)
  when (prevState =/= state) { sentCmd := false.B }
  io.stateOut := state

  // --------------------------------------------------
  // Calculate bit widths for refresh ID composition
  val rankBitsWidth      = log2Ceil(memoryConfig.numberOfRanks)
  val bankGroupBitsWidth = log2Ceil(memoryConfig.numberOfBankGroups)
  val bankBitsWidth      = log2Ceil(memoryConfig.numberOfBanks)
  val columnBitsWidth    = 32 - (rankBitsWidth + bankGroupBitsWidth + bankBitsWidth)

  // unique refresh ID & address fields
  val refreshReqId = Cat(
    0.U(columnBitsWidth.W),
    localConfiguration.rankIndex.U(rankBitsWidth.W),
    localConfiguration.bankGroupIndex.U(bankGroupBitsWidth.W),
    localConfiguration.bankIndex.U(bankBitsWidth.W)
  ).asUInt
  val refreshAddr = refreshReqId

  // --------------------------------------------------
  // Accept incoming request only in Idle
  io.req.ready := (state === sIdle)
  when(state =/= sSref) {
    when(io.req.fire && state === sIdle) {
      reqReg        := io.req.bits
      reqIsRead     := io.req.bits.rd_en
      reqIsWrite    := io.req.bits.wr_en
      reqAddrReg    := io.req.bits.addr
      reqWdataReg   := io.req.bits.wdata
      reqIDReg      := io.req.bits.request_id
      requestActive := true.B
      idleCounter   := 0.U
    } .elsewhen(state === sIdle) {
      idleCounter := idleCounter + 1.U
    }
  }

  // --------------------------------------------------
  // Command register default
  val cmdReg = Wire(new PhysicalMemoryCommand)
  cmdReg.addr       := reqAddrReg
  cmdReg.data       := reqWdataReg
  cmdReg.cs         := true.B
  cmdReg.ras        := false.B
  cmdReg.cas        := false.B
  cmdReg.we         := false.B
  cmdReg.request_id := reqIDReg
  io.cmdOut.bits    := cmdReg

  val issueStates = Seq(sActivate, sRead, sWrite, sPrecharge, sRefresh)
  io.cmdOut.valid := issueStates.map(_ === state).reduce(_ || _) && !sentCmd && !cmdReg.cs

  // --------------------------------------------------
  // Response register
  val respReg = Wire(new ControllerResponse)
  respReg.addr       := reqAddrReg
  respReg.wr_en      := reqIsWrite
  respReg.rd_en      := reqIsRead
  respReg.wdata      := reqWdataReg
  respReg.data       := responseDataReg
  respReg.request_id := reqIDReg
  io.resp.bits       := respReg
  io.resp.valid      := (state === sDone)

  // timing helper
  def elapsed(since: UInt, d: UInt): Bool = (cycleCounter - since) >= d

  // --------------------------------------------------
  // FSM logic
  switch(state) {
    is(sIdle) {
       when(idleCounter >= selfRefreshThreshold && elapsed(lastRefresh, params.tREFI.U)) {
        state := sSrefEnter
      } .elsewhen(elapsed(lastRefresh, params.tREFI.U)) {
        // issue refresh
        reqIDReg   := refreshReqId
        reqAddrReg := refreshAddr
        state      := sRefresh
      } .elsewhen(requestActive) {
        state := sActivate
      }
    }

    is(sSrefEnter) {
      when(!sentCmd) {
        cmdReg.cs  := false.B; cmdReg.ras := false.B; cmdReg.cas := false.B; cmdReg.we := false.B
      }
      when(io.cmdOut.fire) {
        sentCmd := true.B
      }
      when(sentCmd && io.phyResp.fire) {
        state := sSref
        sentCmd := false.B
        printf(p"[Cycle $cycleCounter] CMD FIRE: SREF_ENTER -> BankGroup ${localConfiguration.bankGroupIndex}, Bank ${localConfiguration.bankIndex}\n")
      }
    }

    is(sSref) {
      when(io.req.valid) {
        state := sSrefExit
      }
    }

    is(sSrefExit) {
      when(!sentCmd) {
        cmdReg.cs  := false.B; cmdReg.ras := true.B; cmdReg.cas := true.B; cmdReg.we := true.B
      }
      when(io.cmdOut.fire) {
        sentCmd := true.B
      }
      when(sentCmd && io.phyResp.fire) {
        state := sIdle
        sentCmd := false.B
        printf(p"[Cycle $cycleCounter] CMD FIRE: SREF_EXIT -> BankGroup ${localConfiguration.bankGroupIndex}, Bank ${localConfiguration.bankIndex}\n")
      }
    }

    is(sActivate) {
      when(!sentCmd) {
        cmdReg.cs  := false.B; cmdReg.ras := false.B; cmdReg.cas := true.B; cmdReg.we := true.B
      }
      when(io.cmdOut.fire) {
        sentCmd := true.B
        printf("Issued activate.\n")
      }
      when(sentCmd && !io.phyResp.fire) {
        printf("[Cycle %d]; rdy=%d valid=%d  waiting for ACTIVATE response; addr=%d reqId=%d \n", cycleCounter, io.phyResp.ready, io.phyResp.valid, reqAddrReg, reqIDReg)
        printf("[Cycle %d]; rankIdx=%d bgI=%d bankIdx=%d respIdx=%d respBGI=%d respBI=%d addr=%d reqId=%d \n", cycleCounter, localConfiguration.rankIndex.U, localConfiguration.bankGroupIndex.U, localConfiguration.bankIndex.U, respDec.io.rankIndex, respDec.io.bankGroupIndex, respDec.io.bankIndex, io.phyResp.bits.addr, io.phyResp.bits.request_id)
      }
      when(sentCmd && io.phyResp.fire) {
        lastActivate          := cycleCounter
        activateTimes(actPtr) := cycleCounter
        actPtr                := actPtr + 1.U
        sentCmd               := false.B
        state                 := Mux(reqIsRead, sRead, sWrite)
        printf(p"[Cycle $cycleCounter] CMD FIRE: ACTIVATE -> BankGroup ${localConfiguration.bankGroupIndex}, Bank ${localConfiguration.bankIndex}\n")
      }
    }

    is(sRead) {
      when(!sentCmd) {
        cmdReg.cs  := false.B; cmdReg.ras := true.B; cmdReg.cas := false.B; cmdReg.we := true.B
      }
      when(io.cmdOut.fire) {
        sentCmd       := true.B
        issuedAddrReg := reqAddrReg
        counter       := params.CL.U
      }
      when(sentCmd && io.phyResp.fire && io.phyResp.bits.addr === issuedAddrReg) {
        responseDataReg := io.phyResp.bits.data
        lastReadEnd     := cycleCounter
        sentCmd         := false.B
        state           := sPrecharge
        printf(p"[Cycle $cycleCounter] CMD FIRE: READ -> BankGroup ${localConfiguration.bankGroupIndex}, Bank ${localConfiguration.bankIndex}\n")

      }
    }

    is(sWrite) {
      when(!sentCmd) {
        cmdReg.cs  := false.B; cmdReg.ras := true.B; cmdReg.cas := false.B; cmdReg.we := false.B
      }
      when(io.cmdOut.fire) {
        sentCmd      := true.B
        lastWriteEnd := cycleCounter + params.CWL.U + params.tWR.U
      }
      when(sentCmd && io.phyResp.fire) {
        sentCmd          := false.B
        state            := sPrecharge
        responseDataReg := io.phyResp.bits.data
        printf(p"[Cycle $cycleCounter] CMD FIRE: WRITE -> BankGroup ${localConfiguration.bankGroupIndex}, Bank ${localConfiguration.bankIndex}\n")

      }
    }

    is(sPrecharge) {
      when(!sentCmd) {
        cmdReg.cs  := false.B; cmdReg.ras := false.B; cmdReg.cas := true.B; cmdReg.we := false.B
      }
      when(io.cmdOut.fire) {
        sentCmd := true.B
      }
      when(sentCmd && io.phyResp.fire) {
        lastPrecharge := cycleCounter
        sentCmd       := false.B
        state         := sDone
        printf(p"[Cycle $cycleCounter] CMD FIRE: PRECHARGE -> BankGroup ${localConfiguration.bankGroupIndex}, Bank ${localConfiguration.bankIndex}\n")
      }
    }

    is(sRefresh) {
      when(!sentCmd) {
        cmdReg.cs         := false.B
        cmdReg.ras        := false.B
        cmdReg.cas        := false.B
        cmdReg.we         := true.B
        cmdReg.addr       := refreshAddr
        cmdReg.request_id := refreshReqId
      }
      when(io.cmdOut.fire) {
        sentCmd := true.B
      }
      when(sentCmd && io.phyResp.fire) {
        lastRefresh := cycleCounter
        sentCmd     := false.B
        state       := sIdle
        printf(p"[Cycle $cycleCounter] CMD FIRE: REFRESH -> BankGroup ${localConfiguration.bankGroupIndex}, Bank ${localConfiguration.bankIndex}\n")

      }
    }

    is(sDone) {
      when(io.resp.fire) {
        requestActive := false.B
        state         := sIdle
      }
    }
  }

  // --------------------------------------------------
  // Performance tracker
  if(trackPerformance) {
    val perf = Module(new BankSchedulerPerformanceStatistics(localConfiguration))
    perf.io.in_fire           := io.req.fire
    perf.io.in_bits           := io.req.bits
    perf.io.out_fire          := io.resp.fire
    perf.io.out_bits          := io.resp.bits
    perf.io.mem_request_fire  := io.cmdOut.fire
    perf.io.mem_request_bits  := io.cmdOut.bits
    perf.io.mem_response_fire := io.phyResp.fire
    perf.io.mem_response_bits := io.phyResp.bits
  }

    // --------------------------------------------------
  // Accept phyResp only with matching ID and decoded indices
  val waitingForResp = WireDefault(false.B)
  when(state =/= sSref) {
    waitingForResp := issueStates.map(_ === state).reduce(_ || _) && sentCmd
  }


  io.phyResp.ready := waitingForResp &&
    (io.phyResp.bits.request_id === Mux(state === sSrefEnter || state === sSrefExit, refreshReqId, reqIDReg)) &&
    (respDec.io.rankIndex      === localConfiguration.rankIndex.U) &&
    (respDec.io.bankGroupIndex === localConfiguration.bankGroupIndex.U) &&
    (respDec.io.bankIndex      === localConfiguration.bankIndex.U)
}
