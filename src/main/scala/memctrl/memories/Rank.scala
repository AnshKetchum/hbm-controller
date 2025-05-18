package memctrl

import chisel3._
import chisel3.util._

/** Rank: routes PhysicalMemoryCommand to banks directly, stamps metadata, and arbitrates responses across banks.
  */
class Rank(
  params:           MemoryConfigurationParameters,
  bankParams:       DRAMBankParameters,
  localConfig:      LocalConfigurationParameters,
  trackPerformance: Boolean = false,
  queueDepth:       Int = 256)
    extends PhysicalMemoryModuleBase {
    
  val lastColBank  = RegInit(0.U(32.W))
  val lastColCycle = RegInit(0.U(32.W))

  val cmdDemux = Module(
    new MultiBankCmdQueue(
      params,
      numBanks = params.numberOfBanks,
      depth = queueDepth
    )
  )
  cmdDemux.io.enq <> io.memCmd

  when(io.memCmd.fire) {
    printf("Rank received request")
  }

  val banksWithTiming = Seq.tabulate(params.numberOfBanks) { idx =>
    val cfg     = localConfig.copy(bankIndex = idx)
    val bank    = Module(new DRAMBankWithWait(bankParams, cfg, trackPerformance))
    val timer   = Module(new TimingEngine(bankParams))
    val deqPort = cmdDemux.io.deq(idx)

    // Create BankMemoryCommand with metadata
    val stamped = Wire(Decoupled(new BankMemoryCommand))

    // Manually drive ready/valid
    stamped.valid := deqPort.valid
    deqPort.ready := stamped.ready

    // Fill in the command fields
    stamped.bits.addr             := deqPort.bits.addr
    stamped.bits.data             := deqPort.bits.data
    stamped.bits.cs               := deqPort.bits.cs
    stamped.bits.ras              := deqPort.bits.ras
    stamped.bits.cas              := deqPort.bits.cas
    stamped.bits.we               := deqPort.bits.we
    stamped.bits.request_id       := deqPort.bits.request_id
    stamped.bits.lastColBankGroup := lastColBank
    stamped.bits.lastColCycle     := lastColCycle

    // Debug print for handshake signals (only for bank 0)

    // Debug print
    when(stamped.fire) {
      printf("[Rank] Request enqueued to bank %d with addr 0x%x at cycle %d\n",
        idx.U, stamped.bits.addr, clock.asUInt)
    }

    // Split the command manually
    val stampedForTimer = Wire(Decoupled(new BankMemoryCommand))
    val stampedForBank  = Wire(Decoupled(new BankMemoryCommand))

    // Connect from the shared stamped signal
    stampedForTimer.valid := stamped.valid
    stampedForTimer.bits  := stamped.bits

    stampedForBank.valid := stamped.valid
    stampedForBank.bits  := stamped.bits

    // Producer sees both are ready
    stamped.ready := stampedForTimer.ready && stampedForBank.ready

    // if (idx == 0) {
    //   printf("valid=%d rdy=%d \n", stamped.valid, stamped.ready)
    // }

    // Send the same command to both timing and FSM
    timer.io.cmd    <> stampedForTimer
    bank.io.memCmd  <> stampedForBank
    bank.waitCycles := timer.io.waitCycles

    // Response queue (unchanged)
    val respQ = Module(new Queue(new BankMemoryResponse, queueDepth))
    respQ.io.enq.bits     := bank.io.phyResp.bits
    respQ.io.enq.valid    := bank.io.phyResp.valid
    bank.io.phyResp.ready := respQ.io.enq.ready

    when(respQ.io.enq.fire) {
      lastColBank  := idx.U
      lastColCycle := clock.asUInt
      printf("[Rank] Response enqueued from bank %d at cycle %d\n", idx.U, lastColCycle)
    }

    (bank, respQ)
  }


  val arb = Module(new RRArbiter(new BankMemoryResponse, params.numberOfBanks))
  banksWithTiming.zipWithIndex.foreach { case ((_, q), i) => arb.io.in(i) <> q.io.deq }

  io.phyResp           <> arb.io.out
  io.memCmd.ready      := cmdDemux.io.enq.ready
  io.activeSubMemories := banksWithTiming.map(_._1.io.activeSubMemories).reduce(_ +& _)
}
