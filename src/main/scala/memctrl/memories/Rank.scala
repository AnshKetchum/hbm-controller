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

  // Metadata registers for last column access
  val lastColBank  = RegInit(0.U(32.W))
  val lastColCycle = RegInit(0.U(32.W))

  // --- Command side: per-bank demux queue ---
  val cmdDemux = Module(
    new MultiBankCmdQueue(
      params,
      numBanks = params.numberOfBanks,
      depth = queueDepth
    )
  )
  cmdDemux.io.enq <> io.memCmd

  // Instantiate each Bank and wire commands
  val banks = Seq.tabulate(params.numberOfBanks) { idx =>
    val cfg  = localConfig.copy(bankIndex = idx)
    val bank = Module(new DRAMBank(bankParams, cfg, trackPerformance))

    // Transform PhysicalMemoryCommand -> BankMemoryCommand, stamping metadata
    val in  = cmdDemux.io.deq(idx)
    val out = Wire(Decoupled(new BankMemoryCommand))
    out.valid                 := in.valid
    in.ready                  := out.ready
    out.bits.addr             := in.bits.addr
    out.bits.data             := in.bits.data
    out.bits.cs               := in.bits.cs
    out.bits.ras              := in.bits.ras
    out.bits.cas              := in.bits.cas
    out.bits.we               := in.bits.we
    out.bits.request_id       := in.bits.request_id
    out.bits.lastColBankGroup := lastColBank
    out.bits.lastColCycle     := lastColCycle

    bank.io.memCmd <> out
    bank
  }

  // Channel ready when any bank queue can accept
  io.memCmd.ready := cmdDemux.io.enq.ready

  // --- Response side: per-bank queues + arbiter ---
  val respQs = Seq.fill(params.numberOfBanks) {
    Module(new Queue(new BankMemoryResponse, entries = queueDepth))
  }

  for (((bank, q), idx) <- banks.zip(respQs).zipWithIndex) {
    q.io.enq.bits         := bank.io.phyResp.bits
    q.io.enq.valid        := bank.io.phyResp.valid
    bank.io.phyResp.ready := q.io.enq.ready

    when(q.io.enq.fire) {
      lastColBank  := idx.U
      lastColCycle := clock.asUInt
      printf("[Rank] Response enqueued from bank %d at cycle %d\n", idx.U, lastColCycle)
      printf("  -> request_id = %d, data = 0x%x\n", bank.io.phyResp.bits.request_id, bank.io.phyResp.bits.data)
    }
  }

  // Round-robin arbitrator across banks
  val arb = Module(new RRArbiter(new BankMemoryResponse, params.numberOfBanks))
  for ((q, i) <- respQs.zipWithIndex) arb.io.in(i) <> q.io.deq

  // drive Rank’s phyResp from arbiter
  io.phyResp <> arb.io.out

  // Active banks sum
  io.activeSubMemories := banks.map(_.io.activeSubMemories).reduce(_ +& _)
}
