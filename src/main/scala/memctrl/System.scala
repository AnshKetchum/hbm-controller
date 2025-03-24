package memctrl 

import chisel3._
import chisel3.util._

class MemorySystemIO extends Bundle {
  // User-facing request and response interfaces are decoupled.
  val in  = Flipped(Decoupled(new MemRequest))
  val out = Decoupled(new MemResponse)
}

class SingleChannelSystem(numberOfRanks: Int = 8, numberOfBankGroups: Int = 8, numberOfBanks: Int = 8) extends Module {
  val io = IO(new MemorySystemIO())

  // Instantiate a single channel
  val channel = Module(new Channel(numberOfRanks, numberOfBankGroups, numberOfBanks))
  
  // Instantiate a memory controller 
  val memory_controller = Module(new MemoryController())

  // Connect the controller's command interface to the memory channel.
  // Because memCmd is a Decoupled interface, we use its bits.
  channel.io.cs    := memory_controller.io.memCmd.cs
  channel.io.ras   := memory_controller.io.memCmd.ras
  channel.io.cas   := memory_controller.io.memCmd.cas
  channel.io.we    := memory_controller.io.memCmd.we
  channel.io.addr  := memory_controller.io.memCmd.addr  // address from the controller
  channel.io.wdata := memory_controller.io.memCmd.data   // write data from the controller

  // Connect the channel responses back to the controller.
  memory_controller.io.memResp_valid := channel.io.response_complete
  memory_controller.io.memResp_data  := channel.io.response_data

  // Connect the user interface to the memory controller.
  memory_controller.io.in <> io.in
  io.out <> memory_controller.io.out

  // printf("[System receiving] 0x%x - %x\n", io.in.bits.addr, io.in.bits.wdata)
  // printf("[Mem Controller Sending] %d %d %d %d 0x%x - %x\n", memory_controller.io.memCmd.cs, memory_controller.io.memCmd.ras, memory_controller.io.memCmd.cas, memory_controller.io.memCmd.we, memory_controller.io.memCmd.addr, memory_controller.io.memCmd.data)

}
