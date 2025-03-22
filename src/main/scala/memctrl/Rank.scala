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

    // Calculate bit widths for indexing
    val bankGroupBits = log2Ceil(numberofBankGroups)
    val bankBits = log2Ceil(numberOfBanks)

    // Create a fixed number of bank groups
    val bank_groups = Seq.fill(numberofBankGroups)(Module(new BankGroup(numberOfBanks)))

    // Extract the bank group index from the address
    val bankShift = bankBits
    val bankGroupIndex = io.addr(bankShift + bankGroupBits - 1, bankShift)

    for ((bank_group, i) <- bank_groups.zipWithIndex) {
        val isActiveBankGroup = ~(bankGroupIndex === i.U)

        bank_group.io.cs := isActiveBankGroup
        bank_group.io.ras := io.ras 
        bank_group.io.cas := io.cas 
        bank_group.io.we := io.we
        bank_group.io.addr := io.addr
        bank_group.io.wdata:= io.wdata
    }

    // Default outputs
    val responseCompleteVec = VecInit(bank_groups.map(_.io.response_complete))
    val responseDataVec = VecInit(bank_groups.map(_.io.response_data))

    // Forward output only from the selected rank
    io.response_complete := responseCompleteVec(bankGroupIndex)
    io.response_data := responseDataVec(bankGroupIndex)
}