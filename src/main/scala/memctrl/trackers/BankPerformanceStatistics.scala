package memctrl

import chisel3._
import chisel3.experimental._
import chisel3.util._

/** Monitors requests issued to physical memory.
  *
  * Expects:
  *   - req_fire: asserted when a valid input request is transferred.
  *   - req_bits: the ControllerRequest transferred.
  *   - globalCycle: a cycle count for timestamping.
  */
class BankPhysicalMemoryRequestPerformanceStatistics(
  val rank: Int,
  val bank: Int)
    extends BlackBox(
      Map(
        "RANK" -> rank,
        "BANK" -> bank
      )
    )
    with HasBlackBoxResource {

  val io = IO(new Bundle {
    val clk         = Input(Clock())
    val reset       = Input(Bool())
    val req_fire    = Input(Bool())
    val addr        = Input(UInt(32.W))
    val data        = Input(UInt(32.W))
    val cs          = Input(Bool())
    val ras         = Input(Bool())
    val cas         = Input(Bool())
    val we          = Input(Bool())
    val globalCycle = Input(UInt(64.W))
    val request_id  = Input(UInt(32.W))
  })

  addResource("/vsrc/BankPhysicalMemoryRequestPerformanceStatistics.sv")
}

/** Monitors requests issued to physical memory.
  *
  * Expects:
  *   - req_fire: asserted when a valid input request is transferred.
  *   - req_bits: the ControllerRequest transferred.
  *   - globalCycle: a cycle count for timestamping.
  */
class BankPhysicalMemoryResponsePerformanceStatistics(
  val rank: Int,
  val bank: Int)
    extends BlackBox(
      Map(
        "RANK" -> rank,
        "BANK" -> bank
      )
    )
    with HasBlackBoxResource {

  val io = IO(new Bundle {
    val clk         = Input(Clock())
    val reset       = Input(Bool())
    val resp_fire   = Input(Bool())
    val addr        = Input(UInt(32.W))
    val data        = Input(UInt(32.W))
    val globalCycle = Input(UInt(64.W))
    val request_id  = Input(UInt(32.W))
    val active_row  = Input(UInt(32.W))
    val active_col = Input(UInt(32.W))
  })

  addResource("/vsrc/BankPhysicalMemoryResponsePerformanceStatistics.sv")
}

/** Top-level performance statistics module for the command queue between the controller and physical memory.
  *
  * This module “taps” both the input request and output response streams. The signals:
  *   - in_fire and in_bits represent a successful (fire) input transaction.
  *   - out_fire and out_bits represent a successful (fire) output transaction.
  */
class BankPerformanceStatistics(localConfiguration: LocalConfigurationParameters) extends Module {
  val io = IO(new Bundle {
    val mem_request_fire  = Input(Bool())
    val mem_request_bits  = Input(new PhysicalMemoryCommand)
    val mem_response_fire = Input(Bool())
    val mem_response_bits = Input(new PhysicalMemoryResponse)
    val active_row = Input(UInt(32.W))
    val active_col = Input(UInt(32.W))
  })

  // Global cycle counter (64 bits)
  val cycleCounter = RegInit(0.U(64.W))
  cycleCounter := cycleCounter + 1.U

  // Instantiate the BlackBox modules
  val perfMemRequests  = Module(
    new BankPhysicalMemoryRequestPerformanceStatistics(
      localConfiguration.rankIndex,
      localConfiguration.bankIndex
    )
  )
  val perfMemResponses = Module(
    new BankPhysicalMemoryResponsePerformanceStatistics(
      localConfiguration.rankIndex,
      localConfiguration.bankIndex
    )
  )

  /* Memory Logging Interface */
  // Connect input request logging
  perfMemRequests.io.clk         := clock
  perfMemRequests.io.reset       := reset
  perfMemRequests.io.req_fire    := io.mem_request_fire
  perfMemRequests.io.addr        := io.mem_request_bits.addr
  perfMemRequests.io.data        := io.mem_request_bits.data
  perfMemRequests.io.cs          := io.mem_request_bits.cs
  perfMemRequests.io.ras         := io.mem_request_bits.ras
  perfMemRequests.io.cas         := io.mem_request_bits.cas
  perfMemRequests.io.we          := io.mem_request_bits.we
  perfMemRequests.io.request_id  := io.mem_request_bits.request_id
  perfMemRequests.io.globalCycle := cycleCounter

  // Connect output response logging
  perfMemResponses.io.clk         := clock
  perfMemResponses.io.reset       := reset
  perfMemResponses.io.resp_fire   := io.mem_response_fire
  perfMemResponses.io.addr        := io.mem_response_bits.addr
  perfMemResponses.io.data        := io.mem_response_bits.data
  perfMemResponses.io.request_id  := io.mem_response_bits.request_id
  perfMemResponses.io.globalCycle := cycleCounter
  perfMemResponses.io.active_row  := io.active_row
  perfMemResponses.io.active_col  := io.active_col
}
