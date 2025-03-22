package memctrl 

import chisel3._
import chisel3.util._

class ChannelIO extends Bundle {
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

class Channel(numberOfRanks: Int = 8, numberofBankGroups: Int = 8, numberOfBanks: Int = 8) extends Module {
    val io = IO(new ChannelIO())

    // Create a fixed number of bank groups
    val ranks = Seq.fill(numberOfRanks)(Module(new Rank(numberofBankGroups, numberOfBanks)))

    for (rank <- ranks) {
        rank.io.cs := io.cs
        rank.io.ras := io.ras
        rank.io.cas := io.cas 
        rank.io.we := io.we
        rank.io.addr := io.addr
        rank.io.wdata:= io.wdata
    }

    // Tie the output to the zeroeth value
    io.response_complete := ranks(0).io.response_complete
    io.response_data := ranks(0).io.response_data
}