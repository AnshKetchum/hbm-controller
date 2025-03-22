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

class BankGroup(numberOfBanks: Int = 8) extends Module {
    val io = IO(new BankGroupIO())

    // Create a fix number of banks
    val banks = Seq.fill(numberOfBanks)(Module(new DRAMBank()))

    for (bank <- banks) {
        bank.io.cs := io.cs
        bank.io.ras := io.ras
        bank.io.cas := io.cas 
        bank.io.we := io.we
        bank.io.addr := io.addr
        bank.io.wdata:= io.wdata
    }

    // Tie the output to the zeroeth value
    io.response_complete := banks(0).io.response_complete
    io.response_data := banks(0).io.response_data

}