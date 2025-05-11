package memctrl

import chisel3._
import chisel3.util._

class Rank(
  params: MemoryConfigurationParameters,
  bankParams: DRAMBankParameters,
  localConfig: LocalConfigurationParameters,
  trackPerformance: Boolean = false,
  queueDepth: Int = 256
) extends PhysicalMemoryModuleBase {

  // --- Command side: per–bank-group demux queue ---
  val cmdDemux = Module(new MultiBankGroupCmdQueue(
    params,
    numGroups = params.numberOfBankGroups,
    depth     = queueDepth
  ))
  cmdDemux.io.enq <> io.memCmd

  // Instantiate each BankGroup and hook up its memCmd port
  val groups = Seq.tabulate(params.numberOfBankGroups) { i =>
    val cfg = localConfig.copy(bankGroupIndex = i)
    val bg  = Module(new BankGroup(params, bankParams, cfg, trackPerformance))
    bg.io.memCmd <> cmdDemux.io.deq(i)
    bg
  }

  // --- Response side: original RR-arbiter logic ---
  // Per-group resp queue
  val respQueues = Seq.fill(params.numberOfBankGroups) {
    Module(new Queue(new PhysicalMemoryResponse, entries = queueDepth))
  }

  for ((bg, i) <- groups.zipWithIndex) {
    // enqueue each group's response into its queue
    respQueues(i).io.enq.bits  := bg.io.phyResp.bits
    respQueues(i).io.enq.valid := bg.io.phyResp.valid
    bg.io.phyResp.ready        := respQueues(i).io.enq.ready
  }

  // round-robin across bank groups
  val arbResp = Module(new RRArbiter(new PhysicalMemoryResponse, params.numberOfBankGroups))
  for (i <- 0 until params.numberOfBankGroups) {
    arbResp.io.in(i) <> respQueues(i).io.deq
  }

  // drive Rank’s phyResp from arbiter
  io.phyResp <> arbResp.io.out

  // --- Active-submemory aggregation ---
  val activeVec = VecInit(groups.map(_.io.activeSubMemories))
  io.activeSubMemories := activeVec.reduce(_ +& _)
}
