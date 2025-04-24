package memctrl

import chisel3._
import chisel3.util._

class Channel(params: MemoryConfigurationParameters, bankParams: DRAMBankParameters) extends PhysicalMemoryModuleBase {

  // Address Decoder
  val addrDecoder = Module(new AddressDecoder(params))
  addrDecoder.io.addr := io.memCmd.bits.addr
  val rankIndex = addrDecoder.io.rankIndex

  // Instantiate ranks and per-rank queues
  val ranks = Seq.fill(params.numberOfRanks)(Module(new Rank(params, bankParams)))

  val reqQueues  = Seq.fill(params.numberOfRanks)(Module(new Queue(new PhysicalMemoryCommand, 4)))
  val respQueues = Seq.fill(params.numberOfRanks)(Module(new Queue(new PhysicalMemoryResponse, 4)))

  // Dispatch memCmd to the correct per-rank queue
  for (i <- 0 until params.numberOfRanks) {
    reqQueues(i).io.enq.valid := io.memCmd.valid && (rankIndex === i.U)
    reqQueues(i).io.enq.bits  := io.memCmd.bits
  }

  io.memCmd.ready := reqQueues.map(_.io.enq.ready).zipWithIndex.map {
    case (rdy, i) => Mux(rankIndex === i.U, rdy, false.B)
  }.reduce(_ || _)

  // Connect rank <-> queues
  for (i <- 0 until params.numberOfRanks) {
    ranks(i).io.memCmd <> reqQueues(i).io.deq
    respQueues(i).io.enq <> ranks(i).io.phyResp
  }

  // Response arbiter
  val arbResp = Module(new RRArbiter(new PhysicalMemoryResponse, params.numberOfRanks))
  for (i <- 0 until params.numberOfRanks) {
    arbResp.io.in(i) <> respQueues(i).io.deq
  }

  io.phyResp <> arbResp.io.out

  // Active sub-memories: sum from each rank
  val activeSubMemoriesVec = VecInit(ranks.map(_.io.activeSubMemories))
  io.activeSubMemories := activeSubMemoriesVec.reduce(_ +& _)
}
