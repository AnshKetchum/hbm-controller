package memctrl

import chisel3._
import chisel3.util._

/** BankGroup: routes BankMemoryCommand to banks, tracks and stamps last-column metadata **/
class BankGroup(
    params: MemoryConfigurationParameters,
    bankParams: DRAMBankParameters,
    localConfig: LocalConfigurationParameters, 
    trackPerformance: Boolean = false,
    queueDepth: Int = 256
) extends PhysicalMemoryModuleBase {

  val decoder = Module(new AddressDecoder(params))
  decoder.io.addr := io.memCmd.bits.addr
  val decodedBankIndex = decoder.io.bankIndex

  // Track metadata: where the last column command completed
  val lastColBankGroup = RegInit(0.U(32.W))
  val lastColCycle     = RegInit(0.U(32.W))

  // Instantiate banks
  val banks = Seq.tabulate(params.numberOfBanks) { idx =>
    val cfg = localConfig.copy(bankIndex = idx)
    Module(new DRAMBank(bankParams, cfg, trackPerformance))
  }

  // Per-bank command queues
  val reqQs = Seq.fill(params.numberOfBanks)(Module(new Queue(new BankMemoryCommand, queueDepth)))

  for ((q, idx) <- reqQs.zipWithIndex) {
    q.io.enq.valid := io.memCmd.valid && (decodedBankIndex === idx.U)

    // Build BankMemoryCommand from PhysicalMemoryCommand
    q.io.enq.bits.addr              := io.memCmd.bits.addr
    q.io.enq.bits.data              := io.memCmd.bits.data
    q.io.enq.bits.cs := io.memCmd.bits.cs
    q.io.enq.bits.ras:= io.memCmd.bits.ras
    q.io.enq.bits.cas:= io.memCmd.bits.cas
    q.io.enq.bits.we := io.memCmd.bits.we
    q.io.enq.bits.request_id := io.memCmd.bits.request_id
    q.io.enq.bits.lastColBankGroup  := lastColBankGroup
    q.io.enq.bits.lastColCycle      := lastColCycle

    when(q.io.enq.fire) {
      printf("[BankGroup %d] Cycle %d: enqueued cmd to bank %d addr=0x%x lastGrp=%d lastCyc=%d\n",
        localConfig.bankGroupIndex.U, clock.asUInt, idx.U,
        q.io.enq.bits.addr, q.io.enq.bits.lastColBankGroup, q.io.enq.bits.lastColCycle)
    }

    banks(idx).io.memCmd <> q.io.deq
  }

  io.memCmd.ready := reqQs.zipWithIndex.map { case (q, i) =>
    Mux(decodedBankIndex === i.U, q.io.enq.ready, false.B)
  }.reduce(_ || _)

  // Per-bank response queues
  val respQs = Seq.fill(params.numberOfBanks)(Module(new Queue(new BankMemoryResponse, queueDepth)))

  for ((bank, q) <- banks.zip(respQs)) {
    q.io.enq <> bank.io.phyResp

    when(q.io.enq.fire) {
      // Update metadata on completion
      lastColBankGroup := localConfig.bankGroupIndex.U
      lastColCycle     := clock.asUInt
      printf("[BankGroup %d] Cycle %d: bank responded, update lastGrp=%d lastCyc=%d\n",
        localConfig.bankGroupIndex.U, clock.asUInt, lastColBankGroup, lastColCycle)
    }
  }

  // Arbiter for responses
  val arb = Module(new RRArbiter(new BankMemoryResponse, params.numberOfBanks))
  for ((q, idx) <- respQs.zipWithIndex) {
    arb.io.in(idx) <> q.io.deq
  }

  io.phyResp <> arb.io.out

  // Sum active banks
  io.activeSubMemories := banks.map(_.io.activeSubMemories).reduce(_ +& _)
}
