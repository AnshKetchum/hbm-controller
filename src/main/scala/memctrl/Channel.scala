package memctrl

import chisel3._
import chisel3.util._

class Channel(params: MemoryConfigurationParameters, bankParams: DRAMBankParameters) extends PhysicalMemoryModuleBase {

  // Address Decoder
  val addrDecoder = Module(new AddressDecoder(params))
  addrDecoder.io.addr := io.memCmd.bits.addr
  val rankIndex = addrDecoder.io.rankIndex

  // Instantiate ranks
  val ranks = Seq.fill(params.numberOfRanks)(Module(new Rank(params, bankParams)))

  // Track the number of active sub-memories (ranks) using a register to avoid combinational loop
  val activeSubMemoriesReg = RegInit(0.U(log2Ceil(params.numberOfRanks + 1).W))

  // Propagate active sub-memory state from the ranks
  when (io.memCmd.valid) {
    activeSubMemoriesReg := 0.U // Reset active sub-memories count on valid command
    ranks.zipWithIndex.foreach { case (rank, i) =>
      when (rank.io.activeSubMemories =/= 0.U) {
        activeSubMemoriesReg := activeSubMemoriesReg + 1.U
      }
    }
  }

  // Command wiring: send memCmd to the selected rank
  for ((rank, i) <- ranks.zipWithIndex) {
    val isSelected = rankIndex === i.U
    rank.io.memCmd.bits := io.memCmd.bits
    rank.io.memCmd.valid := io.memCmd.valid && isSelected
  }

  // Set ready signal based on the selected rank
  io.memCmd.ready := Mux1H(
    Seq.tabulate(params.numberOfRanks)(i => (rankIndex === i.U, ranks(i).io.memCmd.ready))
  )

  // Response ready wiring
  for ((rank, i) <- ranks.zipWithIndex) {
    rank.io.phyResp.ready := io.phyResp.ready && (rankIndex === i.U)
  }

  // Mux response valid/bits from selected rank
  val respValidVec = VecInit(ranks.map(_.io.phyResp.valid))
  val respAddrVec  = VecInit(ranks.map(_.io.phyResp.bits.addr))
  val respDataVec  = VecInit(ranks.map(_.io.phyResp.bits.data))

  io.phyResp.valid     := respValidVec(rankIndex)
  io.phyResp.bits.addr := respAddrVec(rankIndex)
  io.phyResp.bits.data := respDataVec(rankIndex)

  // Propagate the number of active sub-memories
  io.activeSubMemories := activeSubMemoriesReg
}
