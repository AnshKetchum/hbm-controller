package memctrl

import chisel3._
import chisel3.util._

/** AddressDecoder: Given a 32-bit address and configuration parameters, it decodes the rank and bank indices.
  *
  * Bit allocation (from LSB):
  *   - bankIndex: [bankBits-1:0]
  *   - rankIndex: [bankBits + rankBits - 1 : bankBits]
  */
class AddressDecoder(params: MemoryConfigurationParameters) extends Module {
  val io = IO(new Bundle {
    val addr      = Input(UInt(32.W))
    val bankIndex = Output(UInt(log2Ceil(params.numberOfBanks).W))
    val rankIndex = Output(UInt(log2Ceil(params.numberOfRanks).W))
  })

  val bankBits = log2Ceil(params.numberOfBanks)
  val rankBits = log2Ceil(params.numberOfRanks)

  io.bankIndex := io.addr(bankBits - 1, 0)
  io.rankIndex := io.addr(bankBits + rankBits - 1, bankBits)
}
