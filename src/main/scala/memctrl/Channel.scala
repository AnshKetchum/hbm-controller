package memctrl

import chisel3._
import chisel3.util._

class ChannelIO extends Bundle {
  // Inputs
  val cs = Input(Bool())
  val ras = Input(Bool())
  val cas = Input(Bool())
  val we = Input(Bool())
  val addr = Input(UInt(32.W))
  val wdata = Input(UInt(32.W))

  // Outputs
  val response_complete = Output(Bool())
  val response_data = Output(UInt(32.W))
}

class Channel(numberOfRanks: Int = 2, numberofBankGroups: Int = 2, numberOfBanks: Int = 2) extends Module {
  val io = IO(new ChannelIO())

  // Calculate bit widths for indexing
  val rankBits = log2Ceil(numberOfRanks)
  val bankGroupBits = log2Ceil(numberofBankGroups)
  val bankBits = log2Ceil(numberOfBanks)

  // Extract the rank index from the address
  val rankShift = bankBits + bankGroupBits
  val rankIndex = io.addr(rankShift + rankBits - 1, rankShift)

  // Instantiate ranks
  val ranks = Seq.fill(numberOfRanks)(Module(new Rank(numberofBankGroups, numberOfBanks)))

  // Wire up ranks
  for ((rank, i) <- ranks.zipWithIndex) {
    val isActiveRank = ~(rankIndex === i.U)

    // Only forward signals to the selected rank
    rank.io.cs := isActiveRank
    rank.io.ras := io.ras
    rank.io.cas := io.cas
    rank.io.we := io.we
    rank.io.addr := io.addr
    rank.io.wdata := io.wdata
  }

  // Default outputs
  val responseCompleteVec = VecInit(ranks.map(_.io.response_complete))
  val responseDataVec = VecInit(ranks.map(_.io.response_data))

  // Forward output only from the selected rank
  io.response_complete := responseCompleteVec(rankIndex)
  io.response_data := responseDataVec(rankIndex)
}
