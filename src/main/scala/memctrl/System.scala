package memctrl 

import chisel3._
import chisel3.util._

class MemorySystemIO extends Bundle {
    val wr_en         = Input(Bool())
    val rd_en         = Input(Bool())
    val addr          = Input(UInt(32.W))
    val wdata         = Input(UInt(32.W))
    val request_valid = Input(Bool())

    // User outputs
    val data          = Output(UInt(32.W))
    val done          = Output(Bool())
}

class SingleChannelSystem(numberOfRanks: Int = 8, numberOfBankGroups: Int = 8, numberOfBanks: Int = 8) extends Module {
    val io = IO(new MemorySystemIO())

    // Instantiate a single channel
    val channel = Module(new Channel(numberOfRanks, numberOfBankGroups, numberOfBanks))

    // Instantiate a memory controller 
    val memory_controller = Module(new MemoryController())

    // Connect the controller to the DRAM.
    channel.io.cs   := memory_controller.io.cs
    channel.io.ras  := memory_controller.io.ras
    channel.io.cas  := memory_controller.io.cas
    channel.io.we   := memory_controller.io.we
    channel.io.addr := memory_controller.io.request_addr  // address comes from the controller
    channel.io.wdata:= memory_controller.io.request_data   // write data from the controller

    // Connect the channel responses back to the controller.
    memory_controller.io.response_complete := channel.io.response_complete
    memory_controller.io.response_data     := channel.io.response_data

    // Connect the user interface.
    memory_controller.io.wr_en         := io.wr_en
    memory_controller.io.rd_en         := io.rd_en
    memory_controller.io.addr          := io.addr
    memory_controller.io.wdata         := io.wdata
    memory_controller.io.request_valid := io.request_valid

    io.data := memory_controller.io.data
    io.done := memory_controller.io.done

}