package memctrl

import chisel3._
import chisel3.util._

class Channel(
  params:           MemoryConfigurationParameters,
  bankParams:       DRAMBankParameters,
  channelIndex:     Int = 0,
  trackPerformance: Boolean = false,
  queueDepth:       Int = 256)
    extends PhysicalMemoryModuleBase {

  // ---- Command side: multi‐rank demux ----
  // Steer incoming commands into per‐rank FIFOs
  val cmdDemux = Module(new MultiRankCmdQueue(params, params.numberOfRanks, queueDepth))
  cmdDemux.io.enq <> io.memCmd // global enqueue

  // Instantiate each Rank and hook up its memCmd port
  val ranks = Seq.tabulate(params.numberOfRanks) { i =>
    val loc   = LocalConfigurationParameters(channelIndex, i, 0)
    val rankM = Module(new Rank(params, bankParams, loc, trackPerformance, queueDepth))
    rankM.io.memCmd <> cmdDemux.io.deq(i)
    rankM
  }

  // Expose per-rank queue depths if desired (optional port)
  // io.reqQueueCounts := cmdDemux.io.counts

  // ---- Response side: original RR‐arbiter logic ----
  // Gather each rank’s responses into a per-rank queue
  val respQueues = Seq.fill(params.numberOfRanks) {
    Module(new Queue(new PhysicalMemoryResponse, entries = queueDepth))
  }

  for ((rankM, i) <- ranks.zipWithIndex) {
    // plug the rank’s phyResp into respQueues(i)
    respQueues(i).io.enq.bits  := rankM.io.phyResp.bits
    respQueues(i).io.enq.valid := rankM.io.phyResp.valid
    rankM.io.phyResp.ready     := respQueues(i).io.enq.ready

    when(respQueues(i).io.enq.fire) {
      printf("[Channel] Response enqueued from Rank %d\n", i.U)
      printf(
        " [Channel]  -> request_id = %d, data = 0x%x\n",
        rankM.io.phyResp.bits.request_id,
        rankM.io.phyResp.bits.data
      )
    }
  }

  // Round-robin across all ranks’ respQueues
  val respArb = Module(new RRArbiter(new PhysicalMemoryResponse, params.numberOfRanks))
  for (i <- 0 until params.numberOfRanks) {
    respArb.io.in(i) <> respQueues(i).io.deq
  }

  // Drive the channel’s phyResp port from the arbiter
  io.phyResp <> respArb.io.out

  // ---- Active‐submemory aggregation ----
  val activeVec = VecInit(ranks.map(_.io.activeSubMemories))
  io.activeSubMemories := activeVec.reduce(_ +& _)
}
