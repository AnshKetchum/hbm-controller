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
class SystemQueuePerformanceStatisticsInput extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk          = Input(Clock())
    val reset        = Input(Bool())
    val req_fire     = Input(Bool())
    val rd_en        = Input(Bool())
    val wr_en        = Input(Bool())
    val addr         = Input(UInt(32.W))
    val globalCycle  = Input(UInt(64.W))
    val request_id = Input(UInt(32.W))
  })

  println("Hi IN")
  addResource("/vsrc/SystemQueuePerformanceStatisticsInput.sv")
}

/** Monitors output responses.
  *
  * Expects:
  *   - resp_fire: asserted when a valid output response is transferred.
  *   - resp_bits: the ControllerResponse transferred.
  *   - globalCycle: the global cycle counter.
  */
class SystemQueuePerformanceStatisticsOutput extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk          = Input(Clock())
    val reset        = Input(Bool())
    val resp_fire    = Input(Bool())
    val rd_en        = Input(Bool())
    val wr_en        = Input(Bool())
    val addr         = Input(UInt(32.W))
    val globalCycle  = Input(UInt(64.W))
    val request_id = Input(UInt(32.W))
  })

  println("Hi OUT")
  addResource("/vsrc/SystemQueuePerformanceStatisticsOutput.sv")
}

/** Top-level performance statistics module.
  *
  * This module “taps” both the input request and output response streams. The signals:
  *   - in_fire and in_bits represent a successful (fire) input transaction.
  *   - out_fire and out_bits represent a successful (fire) output transaction.
  */
class SystemQueuePerformanceStatistics extends Module {
  val io = IO(new Bundle {
    val in_fire  = Input(Bool())
    val in_bits  = Input(new ControllerRequest)
    val out_fire = Input(Bool())
    val out_bits = Input(new ControllerResponse)
  })

  // Global cycle counter (64 bits)
  val cycleCounter = RegInit(0.U(64.W))
  cycleCounter := cycleCounter + 1.U

  // Instantiate the BlackBox modules
  val perfIn  = Module(new SystemQueuePerformanceStatisticsInput)
  val perfOut = Module(new SystemQueuePerformanceStatisticsOutput)

  // Connect clock and reset
  perfIn.io.clk    := clock
  perfIn.io.reset  := reset.asBool
  perfOut.io.clk   := clock
  perfOut.io.reset := reset.asBool

  // Connect input request logging
  perfIn.io.req_fire := io.in_fire
  perfIn.io.rd_en := io.in_bits.rd_en
  perfIn.io.wr_en := io.in_bits.wr_en
  perfIn.io.addr := io.in_bits.addr
  perfIn.io.request_id := io.in_bits.request_id
  perfIn.io.globalCycle := cycleCounter

  // Connect output response logging
  perfOut.io.resp_fire := io.out_fire
  perfOut.io.rd_en := io.out_bits.rd_en
  perfOut.io.wr_en := io.out_bits.wr_en
  perfOut.io.addr := io.out_bits.addr
  perfOut.io.request_id := io.out_bits.request_id
  perfOut.io.globalCycle := cycleCounter
}
