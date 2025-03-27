package memctrl

import chisel3._
import chisel3.util._

/** Updated top-level memory system I/O using the new names. */
class MemorySystemIO extends Bundle {
  // User-facing request and response interfaces are decoupled,
  // now using ControllerRequest and ControllerResponse.
  val in  = Flipped(Decoupled(new ControllerRequest))
  val out = Decoupled(new ControllerResponse)
}

class SingleChannelSystem(
  numberOfRanks: Int = 8,
  numberofBankGroups: Int = 8,
  numberOfBanks: Int = 8
) extends Module {
  val io = IO(new MemorySystemIO())

  val channel = Module(new Channel(numberOfRanks, numberofBankGroups, numberOfBanks))
  val memory_controller = Module(new MultiRankMemoryController(
    numberOfRanks = numberOfRanks,
    numberofBankGroups = numberofBankGroups,
    numberOfBanks = numberOfBanks
  ))

  // Connect the controller's memory command output to the channel's command input.
  channel.io.memCmd <> memory_controller.io.memCmd

  // Connect the channel's physical memory response back to the controller.
  memory_controller.io.phyResp <> channel.io.phyResp

  // Connect the user interface to the memory controller.
  memory_controller.io.in  <> io.in
  io.out                  <> memory_controller.io.out
}
