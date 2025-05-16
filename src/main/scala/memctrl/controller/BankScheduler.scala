package memctrl

import chisel3._
import chisel3.util._
import chisel3.util.log2Ceil

class MemoryControllerFSM(
  params:             DRAMBankParameters,
  localConfiguration: LocalConfigurationParameters,
  memoryConfig:       MemoryConfigurationParameters,
  trackPerformance:   Boolean = false)
    extends Module {
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

  val lastActivate         = RegInit(0.U(64.W))
  val lastPrecharge        = RegInit(0.U(64.W))
  val lastReadEnd          = RegInit(0.U(64.W))
  val lastWriteEnd         = RegInit(0.U(64.W))
  val lastRefresh          = RegInit(0.U(64.W))
  val selfRefreshThreshold = 1000.U
  val activateTimes        = Reg(Vec(memoryConfig.numberOfBanks, UInt(64.W)))
  val actPtr               = RegInit(0.U(log2Ceil(memoryConfig.numberOfBanks).W))

  // --------------------------------------------------
  // Track currently open row for this bank
  val rowBits    = 32 - (log2Ceil(memoryConfig.numberOfRanks) + log2Ceil(memoryConfig.numberOfBankGroups) + log2Ceil(
    memoryConfig.numberOfBanks
  ))
  val invalidRow = Fill(rowBits, 1.U)
  val openRow    = RegInit(invalidRow)

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
  val idleCounter     = RegInit(0.U(32.W))

  // --------------------------------------------------
  // FSM states
  val sIdle :: sActivate :: sRead :: sWrite :: sDone :: sPrecharge :: sRefresh :: sSrefEnter :: sSref :: sSrefExit :: Nil =
    Enum(10)
  val state                                                                                                               = RegInit(sIdle)
  val prevState                                                                                                           = RegNext(state)
  val sentCmd                                                                                                             = RegInit(false.B)
  when(prevState =/= state) { sentCmd := false.B }
  io.stateOut := state

  // --------------------------------------------------
  // Calculate bit widths for refresh ID
  val rankBitsWidth      = log2Ceil(memoryConfig.numberOfRanks)
  val bankGroupBitsWidth = log2Ceil(memoryConfig.numberOfBankGroups)
  val bankBitsWidth      = log2Ceil(memoryConfig.numberOfBanks)
  val columnBitsWidth    = 32 - (rankBitsWidth + bankGroupBitsWidth + bankBitsWidth)

  // unique refresh ID & address
  val refreshReqId = Cat(
    0.U(columnBitsWidth.W),
    localConfiguration.rankIndex.U(rankBitsWidth.W),
    localConfiguration.bankGroupIndex.U(bankGroupBitsWidth.W),
    localConfiguration.bankIndex.U(bankBitsWidth.W)
  )
  val refreshAddr  = refreshReqId

  // --------------------------------------------------
  // Extract row from an address
  def rowField(addr: UInt): UInt = {
    addr(addr.getWidth - 1, bankBitsWidth + bankGroupBitsWidth + rankBitsWidth)
  }
  val reqRow = Wire(UInt(rowBits.W))
  reqRow := rowField(reqAddrReg)

  // --------------------------------------------------
  // Default I/O
  io.req.ready := (state === sIdle)
  val cmdReg = Wire(new PhysicalMemoryCommand)
  cmdReg.addr       := reqAddrReg
  cmdReg.data       := reqWdataReg
  cmdReg.cs         := true.B
  cmdReg.ras        := false.B
  cmdReg.cas        := false.B
  cmdReg.we         := false.B
  cmdReg.request_id := reqIDReg
  io.cmdOut.bits    := cmdReg

  val issueStates = Seq(sActivate, sRead, sWrite, sRefresh)
  io.cmdOut.valid := issueStates.map(_ === state).reduce(_ || _) && !sentCmd && !cmdReg.cs

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
      when(io.req.fire && state === sIdle) {
        reqReg        := io.req.bits
        reqIsRead     := io.req.bits.rd_en
        reqIsWrite    := io.req.bits.wr_en
        reqAddrReg    := io.req.bits.addr
        reqWdataReg   := io.req.bits.wdata
        reqIDReg      := io.req.bits.request_id
        requestActive := true.B
        idleCounter   := 0.U
      }.elsewhen(state === sIdle) {
        idleCounter := idleCounter + 1.U
      }
      when(requestActive) {
        when(idleCounter >= selfRefreshThreshold && elapsed(lastRefresh, params.tREFI.U)) {
          state := sSrefEnter
        }.elsewhen(elapsed(lastRefresh, params.tREFI.U)) {
          reqIDReg   := refreshReqId
          reqAddrReg := refreshAddr
          state      := sRefresh
        }.elsewhen(openRow =/= reqRow) {
          state := sActivate
        }.elsewhen(reqIsRead) {
          state := sRead
        }.otherwise {
          state := sWrite
        }
      }
    }

    is(sActivate) {
      when(!sentCmd) {
        cmdReg.cs := false.B; cmdReg.ras := false.B; cmdReg.cas := true.B; cmdReg.we := true.B
      }
      when(io.cmdOut.fire) {
        sentCmd := true.B
        printf("Issued activate.\n")
      }
      when(sentCmd && io.phyResp.fire) {
        openRow      := reqRow
        lastActivate := cycleCounter
        sentCmd      := false.B
        state        := Mux(reqIsRead, sRead, sWrite)
        printf(p"[Cycle $cycleCounter] CMD FIRE: ACTIVATE\n")
      }
    }

    is(sRead) {
      when(!sentCmd) {
        cmdReg.cs := false.B; cmdReg.ras := true.B; cmdReg.cas := false.B; cmdReg.we := true.B
      }
      when(io.cmdOut.fire) {
        sentCmd       := true.B
        issuedAddrReg := reqAddrReg
      }
      when(sentCmd && io.phyResp.fire && io.phyResp.bits.addr === issuedAddrReg) {
        responseDataReg := io.phyResp.bits.data
        lastReadEnd     := cycleCounter
        sentCmd         := false.B
        state           := sDone // skip precharge for open-page
        printf(p"[Cycle $cycleCounter] CMD FIRE: READ\n")
      }
    }

    is(sWrite) {
      when(!sentCmd) {
        cmdReg.cs := false.B; cmdReg.ras := true.B; cmdReg.cas := false.B; cmdReg.we := false.B
      }
      when(io.cmdOut.fire) {
        sentCmd      := true.B
        lastWriteEnd := cycleCounter + params.CWL.U + params.tWR.U
      }
      when(sentCmd && io.phyResp.fire) {
        sentCmd         := false.B
        responseDataReg := io.phyResp.bits.data
        state           := sDone // skip precharge for open-page
        printf(p"[Cycle $cycleCounter] CMD FIRE: WRITE\n")
      }
    }

    is(sPrecharge) {
      // now unused in open-page policy
      when(!sentCmd) {
        cmdReg.cs := false.B; cmdReg.ras := false.B; cmdReg.cas := true.B; cmdReg.we := false.B
      }
      when(io.cmdOut.fire) {
        sentCmd := true.B
      }
      when(sentCmd && io.phyResp.fire) {
        lastPrecharge := cycleCounter
        sentCmd       := false.B
        state         := sDone
        printf(p"[Cycle $cycleCounter] CMD FIRE: PRECHARGE\n")
      }
    }

    is(sSrefEnter) {
      when(!sentCmd) {
        cmdReg.cs := false.B; cmdReg.ras := false.B; cmdReg.cas := false.B; cmdReg.we := false.B
      }
      when(io.cmdOut.fire) {
        sentCmd := true.B
      }
      when(sentCmd && io.phyResp.fire) {
        state   := sSref
        sentCmd := false.B
      }
    }

    is(sSref) {
      when(io.req.valid) { state := sSrefExit }
    }

    is(sSrefExit) {
      when(!sentCmd) {
        cmdReg.cs := false.B; cmdReg.ras := true.B; cmdReg.cas := true.B; cmdReg.we := true.B
      }
      when(io.cmdOut.fire) { sentCmd := true.B }
      when(sentCmd && io.phyResp.fire) {
        state   := sIdle
        sentCmd := false.B
      }
    }

    is(sDone) {
      when(io.resp.fire) {
        requestActive := false.B
        state         := sIdle
      }
    }

    is(sRefresh) {
      when(!sentCmd) {
        cmdReg.cs         := false.B; cmdReg.ras := false.B; cmdReg.cas := false.B; cmdReg.we := true.B
        cmdReg.addr       := refreshAddr
        cmdReg.request_id := refreshReqId
      }
      when(io.cmdOut.fire) { sentCmd := true.B }
      when(sentCmd && io.phyResp.fire) {
        lastRefresh := cycleCounter
        sentCmd     := false.B
        state       := sIdle
      }
    }
  }

  // --------------------------------------------------
  // Performance tracker
  if (trackPerformance) {
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
    (respDec.io.rankIndex === localConfiguration.rankIndex.U) &&
    (respDec.io.bankGroupIndex === localConfiguration.bankGroupIndex.U) &&
    (respDec.io.bankIndex === localConfiguration.bankIndex.U)
}
