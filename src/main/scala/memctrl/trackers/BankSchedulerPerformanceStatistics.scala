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
class BankSchedulerPerformanceStatisticsInput(
  val rank:      Int,
  val bankgroup: Int,
  val bank:      Int)
    extends BlackBox(
      Map(
        "RANK"      -> rank,
        "BANKGROUP" -> bankgroup,
        "BANK"      -> bank
      )
    )
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk         = Input(Clock())
    val reset       = Input(Bool())
    val req_fire    = Input(Bool())
    val rd_en       = Input(Bool())
    val wr_en       = Input(Bool())
    val addr        = Input(UInt(32.W))
    val globalCycle = Input(UInt(64.W))
    val request_id  = Input(UInt(32.W))
  })

  addResource("/vsrc/BankSchedulerPerformanceStatisticsInput.sv")
}

/** Monitors requests issued to physical memory.
  *
  * Expects:
  *   - req_fire: asserted when a valid input request is transferred.
  *   - req_bits: the ControllerRequest transferred.
  *   - globalCycle: a cycle count for timestamping.
  */
class BankSchedulerPhysicalMemoryRequestPerformanceStatistics(
  val rank:      Int,
  val bankgroup: Int,
  val bank:      Int)
    extends BlackBox(
      Map(
        "RANK"      -> rank,
        "BANKGROUP" -> bankgroup,
        "BANK"      -> bank
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

  addResource("/vsrc/BankSchedulerPhysicalMemoryRequestPerformanceStatistics.sv")
}

/** Monitors requests issued to physical memory.
  *
  * Expects:
  *   - req_fire: asserted when a valid input request is transferred.
  *   - req_bits: the ControllerRequest transferred.
  *   - globalCycle: a cycle count for timestamping.
  */
class BankSchedulerPhysicalMemoryResponsePerformanceStatistics(
  val rank:      Int,
  val bankgroup: Int,
  val bank:      Int)
    extends BlackBox(
      Map(
        "RANK"      -> rank,
        "BANKGROUP" -> bankgroup,
        "BANK"      -> bank
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
  })

  addResource("/vsrc/BankSchedulerPhysicalMemoryResponsePerformanceStatistics.sv")
}

/** Monitors output responses.
  *
  * Expects:
  *   - resp_fire: asserted when a valid output response is transferred.
  *   - resp_bits: the ControllerResponse transferred.
  *   - globalCycle: the global cycle counter.
  */
class BankSchedulerPerformanceStatisticsOutput(
  val rank:      Int,
  val bankgroup: Int,
  val bank:      Int)
    extends BlackBox(
      Map(
        "RANK"      -> rank,
        "BANKGROUP" -> bankgroup,
        "BANK"      -> bank
      )
    )
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk         = Input(Clock())
    val reset       = Input(Bool())
    val resp_fire   = Input(Bool())
    val rd_en       = Input(Bool())
    val wr_en       = Input(Bool())
    val addr        = Input(UInt(32.W))
    val globalCycle = Input(UInt(64.W))
    val request_id  = Input(UInt(32.W))
  })

  addResource("/vsrc/BankSchedulerPerformanceStatisticsOutput.sv")
}

/** Top-level performance statistics module for the command queue between the controller and physical memory.
  *
  * This module “taps” both the input request and output response streams. The signals:
  *   - in_fire and in_bits represent a successful (fire) input transaction.
  *   - out_fire and out_bits represent a successful (fire) output transaction.
  */
class BankSchedulerPerformanceStatistics(localConfiguration: LocalConfigurationParameters) extends Module {
  val io = IO(new Bundle {
    val in_fire           = Input(Bool())
    val in_bits           = Input(new ControllerRequest)
    val out_fire          = Input(Bool())
    val out_bits          = Input(new ControllerResponse)
    val mem_request_fire  = Input(Bool())
    val mem_request_bits  = Input(new PhysicalMemoryCommand)
    val mem_response_fire = Input(Bool())
    val mem_response_bits = Input(new PhysicalMemoryResponse)
  })

  // Global cycle counter (64 bits)
  val cycleCounter = RegInit(0.U(64.W))
  cycleCounter := cycleCounter + 1.U

  // Instantiate the BlackBox modules
  val perfIn           = Module(
    new BankSchedulerPerformanceStatisticsInput(
      localConfiguration.rankIndex,
      localConfiguration.bankIndex
    )
  )
  val perfOut          = Module(
    new BankSchedulerPerformanceStatisticsOutput(
      localConfiguration.rankIndex,
      localConfiguration.bankIndex
    )
  )
  val perfMemRequests  = Module(
    new BankSchedulerPhysicalMemoryRequestPerformanceStatistics(
      localConfiguration.rankIndex,
      localConfiguration.bankIndex
    )
  )
  val perfMemResponses = Module(
    new BankSchedulerPhysicalMemoryResponsePerformanceStatistics(
      localConfiguration.rankIndex,
      localConfiguration.bankIndex
    )
  )

  // // Connect clock and reset
  // Seq(perfIn.io, perfOut.io, perfMemRequests.io, perfMemResponses.io).foreach { bb =>
  //   bbi.clk   := io.clk
  //   bbi.reset := io.reset.asBool
  // }

  /* Request / Response Interface */
  // Connect input request logging
  perfIn.io.clk         := clock
  perfIn.io.reset       := reset
  perfIn.io.req_fire    := io.in_fire
  perfIn.io.rd_en       := io.in_bits.rd_en
  perfIn.io.wr_en       := io.in_bits.wr_en
  perfIn.io.addr        := io.in_bits.addr
  perfIn.io.request_id  := io.in_bits.request_id
  perfIn.io.globalCycle := cycleCounter

  // Connect output response logging
  perfOut.io.clk         := clock
  perfOut.io.reset       := reset
  perfOut.io.resp_fire   := io.out_fire
  perfOut.io.rd_en       := io.out_bits.rd_en
  perfOut.io.wr_en       := io.out_bits.wr_en
  perfOut.io.addr        := io.out_bits.addr
  perfOut.io.request_id  := io.out_bits.request_id
  perfOut.io.globalCycle := cycleCounter

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
}
