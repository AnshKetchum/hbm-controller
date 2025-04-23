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

  // Wire command to all ranks, only one gets valid
  for ((rank, i) <- ranks.zipWithIndex) {
    val isSelected = rankIndex === i.U
    rank.io.memCmd.bits  := io.memCmd.bits
    rank.io.memCmd.valid := io.memCmd.valid && isSelected
  }

  io.memCmd.ready := Mux1H(
    Seq.tabulate(params.numberOfRanks)(i => (rankIndex === i.U, ranks(i).io.memCmd.ready))
  )

  // Wire response ready to all ranks (only one gets true)
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
}
