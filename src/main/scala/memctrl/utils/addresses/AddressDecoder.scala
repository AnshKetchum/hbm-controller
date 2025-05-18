package memctrl

import chisel3._
import chisel3.util._

class AddressDecoder(params: MemoryConfigurationParameters, dramParams: DRAMBankParameters) extends Module {
  val io = IO(new Bundle {
    val addr         = Input(UInt(32.W))
    val channelIndex = Output(UInt(log2Ceil(params.numberOfChannels).max(1).W))
    val bankIndex    = Output(UInt(log2Ceil(params.numberOfBanks).max(1).W))
    val rankIndex    = Output(UInt(log2Ceil(params.numberOfRanks).max(1).W))
    val rowIndex     = Output(UInt(log2Ceil(dramParams.numRows).max(1).W))
    val columnIndex  = Output(UInt(log2Ceil(dramParams.numCols).max(1).W))
  })

  val channelBits = log2Ceil(params.numberOfChannels)
  val bankBits    = log2Ceil(params.numberOfBanks)
  val rankBits    = log2Ceil(params.numberOfRanks)
  val rowBits     = log2Ceil(dramParams.numRows)
  val colBits     = log2Ceil(dramParams.numCols)

  val bankStart = channelBits
  val rankStart = bankStart + bankBits
  val rowStart  = rankStart + rankBits
  val colStart  = rowStart + rowBits

  // channelIndex
  if (channelBits > 0) {
    io.channelIndex := io.addr(channelBits - 1, 0)
  } else {
    io.channelIndex := 0.U
  }

  // bankIndex
  if (bankBits > 0) {
    io.bankIndex := io.addr(rankStart - 1, bankStart)
  } else {
    io.bankIndex := 0.U
  }

  // rankIndex
  if (rankBits > 0) {
    io.rankIndex := io.addr(rowStart - 1, rankStart)
  } else {
    io.rankIndex := 0.U
  }

  // rowIndex
  if (rowBits > 0) {
    io.rowIndex := io.addr(colStart - 1, rowStart)
  } else {
    io.rowIndex := 0.U
  }

  // columnIndex
  if (colBits > 0) {
    io.columnIndex := io.addr(31, colStart)
  } else {
    io.columnIndex := 0.U
  }
}
