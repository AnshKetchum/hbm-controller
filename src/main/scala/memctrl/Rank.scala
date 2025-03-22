package memctrl 

import chisel3._
import chisel3.util._

class RankIO extends Bundle {
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

class Rank(numberofBankGroups: Int = 8, numberOfBanks: Int = 8) extends Module {
    val io = IO(new RankIO())

    // Create a fixed number of bank groups
    val bank_groups = Seq.fill(numberofBankGroups)(Module(new BankGroup(numberOfBanks)))

    for (bank_group <- bank_groups) {
        bank_group.io.cs := io.cs
        bank_group.io.ras := io.ras
        bank_group.io.cas := io.cas 
        bank_group.io.we := io.we
        bank_group.io.addr := io.addr
        bank_group.io.wdata:= io.wdata
    }

    // Tie the output to the zeroeth value
    io.response_complete := bank_groups(0).io.response_complete
    io.response_data := bank_groups(0).io.response_data
}