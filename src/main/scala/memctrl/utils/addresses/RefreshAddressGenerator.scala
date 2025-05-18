package memctrl

import chisel3._
import chisel3.util._
import chisel3.util.log2Ceil

/** Generates a refresh request ID and corresponding refresh address based on the current local bank configuration and
  * global memory/dram parameters.
  */
class RefreshAddressGenerator(
  memoryConfig: MemoryConfigurationParameters,
  dramParams:   DRAMBankParameters,
  localConfig:  LocalConfigurationParameters)
    extends Module {
  private val channelBitsWidth = log2Ceil(memoryConfig.numberOfChannels)
  private val bankBitsWidth    = log2Ceil(memoryConfig.numberOfBanks)
  private val rankBitsWidth    = log2Ceil(memoryConfig.numberOfRanks)
  private val rowBitsWidth     = log2Ceil(dramParams.numRows)
  private val colBitsWidth     = log2Ceil(dramParams.numCols)

  // total width of the composed ID/address
  private val totalWidth = channelBitsWidth + bankBitsWidth + rankBitsWidth + rowBitsWidth + colBitsWidth

  val io = IO(new Bundle {

    /** Unique refresh request identifier { zeros[row+col], rank, bank, channel }
      */
    val refreshReqId = Output(UInt(totalWidth.W))

    /** Address field for refresh { zeros[col], zeros[row], rank, bank, channel }
      */
    val refreshAddr = Output(UInt(totalWidth.W))
  })

  // build the two values
  io.refreshReqId := Cat(
    0.U((rowBitsWidth + colBitsWidth).W),
    localConfig.rankIndex.U(rankBitsWidth.W),
    localConfig.bankIndex.U(bankBitsWidth.W),
    localConfig.channelIndex.U(channelBitsWidth.W)
  )

  io.refreshAddr := Cat(
    0.U(colBitsWidth.W),
    0.U(rowBitsWidth.W),
    localConfig.rankIndex.U(rankBitsWidth.W),
    localConfig.bankIndex.U(bankBitsWidth.W),
    localConfig.channelIndex.U(channelBitsWidth.W)
  )
}
