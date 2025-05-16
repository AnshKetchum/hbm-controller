// Updated BankGroup.scala
package memctrl

import chisel3._
import chisel3.util._

/** BankGroup: routes PhysicalMemoryCommand to banks, stamps metadata, and arbiteresponses */
class BankGroup(
  params:           MemoryConfigurationParameters,
  bankParams:       DRAMBankParameters,
  localConfig:      LocalConfigurationParameters,
  trackPerformance: Boolean = false,
  queueDepth:       Int = 256)
    extends PhysicalMemoryModuleBase {

  // Metadata registers
  val lastColBankGroup = RegInit(0.U(32.W))
  val lastColCycle     = RegInit(0.U(32.W))

  // --- Command side: per-bank demux ---
  val cmdDemux = Module(new MultiBankCmdQueue(params, params.numberOfBanks, queueDepth))
  cmdDemux.io.enq <> io.memCmd

  // Instantiate banks and wire commands
  val banks = Seq.tabulate(params.numberOfBanks) { idx =>
    val cfg  = localConfig.copy(bankIndex = idx)
    val bank = Module(new DRAMBank(bankParams, cfg, trackPerformance))

    // Transform PhysicalMemoryCommand -> BankMemoryCommand
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
    out.bits.lastColBankGroup := lastColBankGroup
    out.bits.lastColCycle     := lastColCycle

    bank.io.memCmd <> out
    bank
  }

  // Channel ready when selected bank queue ready
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
      lastColBankGroup := localConfig.bankGroupIndex.U
      lastColCycle     := clock.asUInt
      printf("[BankGroup] Response enqueued from bank %d\n", idx.U)
      printf("Response: request_id = %d, data = 0x%x\n", bank.io.phyResp.bits.request_id, bank.io.phyResp.bits.data)
    }
  }

  val arb = Module(new RRArbiter(new BankMemoryResponse, params.numberOfBanks))
  for ((q, i) <- respQs.zipWithIndex) arb.io.in(i) <> q.io.deq

  io.phyResp <> arb.io.out

  // Active banks sum
  io.activeSubMemories := banks.map(_.io.activeSubMemories).reduce(_ +& _)
}
