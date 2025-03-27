package memctrl

import chisel3._
import chisel3.util._


class ChannelIO extends Bundle {
  // Input from the memory controller: now a single Decoupled MemCmd interface.
  val memCmd = Flipped(Decoupled(new MemCmd))

  // Output: Decoupled physical memory response.
  val phyResp = Decoupled(new PhysicalMemResponse)
}

class Channel(numberOfRanks: Int = 2, numberofBankGroups: Int = 2, numberOfBanks: Int = 2) extends Module {
  val io = IO(new ChannelIO())

  val rankBits      = log2Ceil(numberOfRanks)
  val bankGroupBits = log2Ceil(numberofBankGroups)
  val bankBits      = log2Ceil(numberOfBanks)

  val rankShift = bankBits + bankGroupBits
  val rankIndex = io.memCmd.bits.addr(rankShift + rankBits - 1, rankShift)

  val ranks = Seq.fill(numberOfRanks)(Module(new Rank(numberofBankGroups, numberOfBanks)))

  for ((rank, i) <- ranks.zipWithIndex) {
    val isActiveRank = (rankIndex === i.U)
    rank.io.cs    := Mux(isActiveRank, io.memCmd.bits.cs, false.B)
    rank.io.ras   := Mux(isActiveRank, io.memCmd.bits.ras, false.B)
    rank.io.cas   := Mux(isActiveRank, io.memCmd.bits.cas, false.B)
    rank.io.we    := Mux(isActiveRank, io.memCmd.bits.we, false.B)
    rank.io.addr  := io.memCmd.bits.addr
    rank.io.wdata := io.memCmd.bits.data
  }

  val responseCompleteVec = VecInit(ranks.map(_.io.response_complete))
  val responseDataVec     = VecInit(ranks.map(_.io.response_data))

  io.phyResp.valid := responseCompleteVec(rankIndex)
  io.phyResp.bits.addr := io.memCmd.bits.addr
  io.phyResp.bits.data := responseDataVec(rankIndex)

  // Decoupled logic for handling memory commands
  io.memCmd.ready := true.B  // Accept all commands (modify if needed)
}
