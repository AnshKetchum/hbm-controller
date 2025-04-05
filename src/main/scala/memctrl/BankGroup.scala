package memctrl

import chisel3._ 
import chisel3.util._

/** BankGroup Module
  * Uses AddressDecoder to extract the bank index.
  */
class BankGroupIO extends Bundle {
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

class BankGroup(params: MemoryConfigurationParams, bankParams: DRAMBankParams) extends Module {
  val io = IO(new BankGroupIO)

  // Use AddressDecoder to extract the bank index from the address
  val addrDecoder = Module(new AddressDecoder(params))
  addrDecoder.io.addr := io.addr

  val bankIndex = addrDecoder.io.bankIndex

  // Instantiate banks
  val banks = Seq.fill(params.numberOfBanks)(Module(new DRAMBank(bankParams)))
  for ((bank, i) <- banks.zipWithIndex) {
    // Again, preserve the original inverted active signal logic.
    val isActiveBank = ~(bankIndex === i.U)
    bank.io.cs    := isActiveBank
    bank.io.ras   := io.ras
    bank.io.cas   := io.cas
    bank.io.we    := io.we
    bank.io.addr  := io.addr
    bank.io.wdata := io.wdata
  }

  // Aggregate bank responses.
  val responseCompleteVec = VecInit(banks.map(_.io.response_complete))
  val responseDataVec     = VecInit(banks.map(_.io.response_data))

  io.response_complete := responseCompleteVec(bankIndex)
  io.response_data     := responseDataVec(bankIndex)
}