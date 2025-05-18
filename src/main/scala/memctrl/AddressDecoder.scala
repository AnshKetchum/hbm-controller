package memctrl

import chisel3._
import chisel3.util._

class AddressDecoder(params: MemoryConfigurationParameters) extends Module {
  val io = IO(new Bundle {
    val addr         = Input(UInt(32.W))
    val channelIndex = Output(UInt(log2Ceil(params.numberOfChannels).max(1).W))
    val bankIndex    = Output(UInt(log2Ceil(params.numberOfBanks).max(1).W))
    val rankIndex    = Output(UInt(log2Ceil(params.numberOfRanks).max(1).W))
  })

  val channelBits = log2Ceil(params.numberOfChannels)
  val bankBits    = log2Ceil(params.numberOfBanks)
  val rankBits    = log2Ceil(params.numberOfRanks)

  // channelIndex
  if (channelBits > 0) {
    io.channelIndex := io.addr(channelBits - 1, 0)
  } else {
    io.channelIndex := 0.U
  }

  // bankIndex
  if (bankBits > 0) {
    io.bankIndex := io.addr(channelBits + bankBits - 1, channelBits)
  } else {
    io.bankIndex := 0.U
  }

  // rankIndex
  if (rankBits > 0) {
    io.rankIndex := io.addr(channelBits + bankBits + rankBits - 1, channelBits + bankBits)
  } else {
    io.rankIndex := 0.U
  }
}
