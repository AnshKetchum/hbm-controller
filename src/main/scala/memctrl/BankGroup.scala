package memctrl

import chisel3._ 
import chisel3.util._

class BankGroupIO extends Bundle {
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

class BankGroup(numberOfBanks: Int = 1) extends Module {
  val io = IO(new BankGroupIO())

  // Calculate bit width for bank indexing
  val bankBits = log2Ceil(numberOfBanks)

  // Extract bank index from the address (lowest bits)
  val bankIndex = io.addr(bankBits - 1, 0)

  // Instantiate banks
  val banks = Seq.fill(numberOfBanks)(Module(new DRAMBank()))

  // Wire banks conditionally based on bank index
  for ((bank, i) <- banks.zipWithIndex) {
    val isActiveBank = ~(bankIndex === i.U)

    bank.io.cs := isActiveBank
    bank.io.ras := io.ras
    bank.io.cas := io.cas
    bank.io.we := io.we
    bank.io.addr := io.addr
    bank.io.wdata := io.wdata
    // printf("[Bank Group] sending: %d\n", io.addr)
  }

  // Select outputs from the active bank
  val responseCompleteVec = VecInit(banks.map(_.io.response_complete))
  val responseDataVec = VecInit(banks.map(_.io.response_data))

  io.response_complete := responseCompleteVec(bankIndex)
  io.response_data := responseDataVec(bankIndex)
}
