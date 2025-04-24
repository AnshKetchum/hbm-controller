package memctrl

import chisel3._
import chisel3.util._

class Rank(params: MemoryConfigurationParameters, bankParams: DRAMBankParameters) extends PhysicalMemoryModuleBase {

  val decoder     = Module(new AddressDecoder(params))
  decoder.io.addr := io.memCmd.bits.addr
  val bgIndex     = decoder.io.bankGroupIndex

  // Instantiate BankGroups and per-bank-group queues
  val groups      = Seq.fill(params.numberOfBankGroups)(Module(new BankGroup(params, bankParams)))
  val reqQueues   = Seq.fill(params.numberOfBankGroups)(Module(new Queue(new PhysicalMemoryCommand, 4)))
  val respQueues  = Seq.fill(params.numberOfBankGroups)(Module(new Queue(new PhysicalMemoryResponse, 4)))

  // Demux command to appropriate request queue
  for (i <- 0 until params.numberOfBankGroups) {
    reqQueues(i).io.enq.valid := io.memCmd.valid && (bgIndex === i.U)
    reqQueues(i).io.enq.bits  := io.memCmd.bits
  }

  io.memCmd.ready := reqQueues.map(_.io.enq.ready).zipWithIndex.map {
    case (rdy, i) => Mux(bgIndex === i.U, rdy, false.B)
  }.reduce(_ || _)

  // Connect queues to bank groups
  for (i <- 0 until params.numberOfBankGroups) {
    groups(i).io.memCmd <> reqQueues(i).io.deq
    respQueues(i).io.enq <> groups(i).io.phyResp
  }

  // Arbiter to choose one response to send out
  val arbResp = Module(new RRArbiter(new PhysicalMemoryResponse, params.numberOfBankGroups))
  for (i <- 0 until params.numberOfBankGroups) {
    arbResp.io.in(i) <> respQueues(i).io.deq
  }

  io.phyResp <> arbResp.io.out

  // Active sub-memories: sum across all bank groups
  val activeSubMemoriesVec = VecInit(groups.map(_.io.activeSubMemories))
  io.activeSubMemories := activeSubMemoriesVec.reduce(_ +& _)
}
