package memctrl

import chisel3._
import chisel3.util._

/** AddressDecoder: Given a 32-bit address and configuration parameters, it decodes the rank, bank group, and bank
  * indices.
  *
  * Bit allocation (from LSB):
  *   - bankIndex: [bankBits-1:0]
  *   - bankGroupIndex: [bankBits + bankGroupBits - 1 : bankBits]
  *   - rankIndex: [bankBits + bankGroupBits + rankBits - 1 : bankBits + bankGroupBits]
  */
class AddressDecoder(params: MemoryConfigurationParameters) extends Module {
  val io = IO(new Bundle {
    val addr           = Input(UInt(32.W))
    val bankIndex      = Output(UInt(log2Ceil(params.numberOfBanks).W))
    val bankGroupIndex = Output(UInt(log2Ceil(params.numberOfBankGroups).W))
    val rankIndex      = Output(UInt(log2Ceil(params.numberOfRanks).W))
  })

  val bankBits      = log2Ceil(params.numberOfBanks)
  val bankGroupBits = log2Ceil(params.numberOfBankGroups)
  val rankBits      = log2Ceil(params.numberOfRanks)

  io.bankIndex      := io.addr(bankBits - 1, 0)
  io.bankGroupIndex := io.addr(bankBits + bankGroupBits - 1, bankBits)
  io.rankIndex      := io.addr(bankBits + bankGroupBits + rankBits - 1, bankBits + bankGroupBits)
}
