package memctrl

import chisel3._
import chisel3.experimental._
import chisel3.util._

/** Monitors input requests.
  *
  * Expects:
  *   - req_fire: asserted when a valid input request is transferred.
  *   - req_bits: the ControllerRequest transferred.
  *   - globalCycle: a cycle count for timestamping.
  */
class CommandQueuePerformanceStatisticsInput extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk          = Input(Clock())
    val reset        = Input(Bool())
    val req_fire     = Input(Bool())
    val addr         = Input(UInt(32.W))
    val data         = Input(UInt(32.W))
    val cs           = Input(Bool())
    val ras          = Input(Bool())
    val cas          = Input(Bool())
    val we           = Input(Bool())
    val globalCycle  = Input(UInt(64.W))
    val request_id = Input(UInt(32.W))
  })

  addResource("/vsrc/CommandQueuePerformanceStatisticsInput.sv")
}

/** Monitors output responses.
  *
  * Expects:
  *   - resp_fire: asserted when a valid output response is transferred.
  *   - resp_bits: the ControllerResponse transferred.
  *   - globalCycle: the global cycle counter.
  */
class CommandQueuePerformanceStatisticsOutput extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk          = Input(Clock())
    val reset        = Input(Bool())
    val resp_fire    = Input(Bool())
    val addr         = Input(UInt(32.W))
    val data         = Input(UInt(32.W))
    val globalCycle  = Input(UInt(64.W))
    val request_id = Input(UInt(32.W))
  })

  addResource("/vsrc/CommandQueuePerformanceStatisticsOutput.sv")
}


/** Top-level performance statistics module for the command queue between the controller and physical memory.
  *
  * This module “taps” both the input request and output response streams.
  * The signals:
  *   - in_fire and in_bits represent a successful (fire) input transaction.
  *   - out_fire and out_bits represent a successful (fire) output transaction.
  */
class CommandQueuePerformanceStatistics extends Module {
  val io = IO(new Bundle {
    val in_fire  = Input(Bool())
    val in_bits  = Input(new PhysicalMemoryCommand)
    val out_fire = Input(Bool())
    val out_bits = Input(new PhysicalMemoryResponse)
  })

  // Global cycle counter (64 bits)
  val cycleCounter = RegInit(0.U(64.W))
  cycleCounter := cycleCounter + 1.U

  // Instantiate the BlackBox modules
  val perfIn  = Module(new CommandQueuePerformanceStatisticsInput)
  val perfOut = Module(new CommandQueuePerformanceStatisticsOutput)

  // Connect clock and reset
  perfIn.io.clk := clock
  perfIn.io.reset := reset.asBool
  perfOut.io.clk := clock
  perfOut.io.reset := reset.asBool

  // Connect input request logging
  perfIn.io.req_fire := io.in_fire
  perfIn.io.addr     := io.in_bits.addr
  perfIn.io.data     := io.in_bits.data
  perfIn.io.cs       := io.in_bits.cs
  perfIn.io.ras      := io.in_bits.ras
  perfIn.io.cas      := io.in_bits.cas
  perfIn.io.we       := io.in_bits.we
  perfIn.io.request_id := io.in_bits.request_id
  perfIn.io.globalCycle := cycleCounter

  // Connect output response logging
  perfOut.io.resp_fire := io.out_fire
  perfOut.io.addr := io.out_bits.addr
  perfOut.io.data := io.out_bits.data
  perfOut.io.request_id := io.out_bits.request_id
  perfOut.io.globalCycle := cycleCounter
}
