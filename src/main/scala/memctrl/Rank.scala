package memctrl 

import chisel3._
import chisel3.util._

/** Rank Module
  * Uses AddressDecoder to extract the bank group index.
  */
class RankIO extends Bundle {
  // Inputs
  val cs    = Input(Bool())
  val ras   = Input(Bool())
  val cas   = Input(Bool())
  val we    = Input(Bool())
  val addr  = Input(UInt(32.W))
  val wdata = Input(UInt(32.W))
  // Outputs
  val response_complete = Output(Bool())
  val response_data     = Output(UInt(32.W))
}

class Rank(params: MemoryConfigurationParams = MemoryConfigurationParams(), bankParams: DRAMBankParams = DRAMBankParams()) extends Module {
  val io = IO(new RankIO)

  // Use AddressDecoder to get the bank group index from the address
  val addrDecoder = Module(new AddressDecoder(params))
  addrDecoder.io.addr := io.addr

  val bankGroupIndex = addrDecoder.io.bankGroupIndex

  // Instantiate bank groups
  val bank_groups = Seq.fill(params.numberOfBankGroups)(Module(new BankGroup(params, bankParams)))
  for ((bank_group, i) <- bank_groups.zipWithIndex) {
    // Note: The original code inverted the equality to drive chip select.
    // We preserve that logic here.
    val isActiveBankGroup = ~(bankGroupIndex === i.U)
    bank_group.io.cs    := isActiveBankGroup
    bank_group.io.ras   := io.ras 
    bank_group.io.cas   := io.cas 
    bank_group.io.we    := io.we
    bank_group.io.addr  := io.addr
    bank_group.io.wdata := io.wdata
  }

  // Aggregate responses from bank groups.
  val responseCompleteVec = VecInit(bank_groups.map(_.io.response_complete))
  val responseDataVec     = VecInit(bank_groups.map(_.io.response_data))

  io.response_complete := responseCompleteVec(bankGroupIndex)
  io.response_data     := responseDataVec(bankGroupIndex)
}