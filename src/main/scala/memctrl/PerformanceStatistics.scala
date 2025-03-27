package memctrl

import chisel3._
import chisel3.util._

/** Monitors input requests.
  *
  * Expects:
  *   - req_fire: asserted when a valid input request is transferred.
  *   - req_bits: the ControllerRequest transferred.
  *   - globalCycle: a cycle count for timestamping.
  */
class PerformanceStatisticsInput extends Module {
  val io = IO(new Bundle {
    val req_fire    = Input(Bool())
    val req_bits    = Input(new ControllerRequest)
    val globalCycle = Input(UInt(64.W))
  })

  val reqId = RegInit(0.U(32.W))

  // Print CSV header at reset.
  when (reset.asBool) {
    printf("input_stats.csv: RequestID,Read,Write,Cycle\n")
  }

  when (io.req_fire) {
    printf("input_stats.csv: %d,%d,%d,%d\n",
      reqId,
      io.req_bits.rd_en,
      io.req_bits.wr_en,
      io.globalCycle)
    reqId := reqId + 1.U
  }
}

/** Monitors output responses.
  *
  * Expects:
  *   - resp_fire: asserted when a valid output response is transferred.
  *   - resp_bits: the ControllerResponse transferred.
  *   - globalCycle: the global cycle counter.
  */
class PerformanceStatisticsOutput extends Module {
  val io = IO(new Bundle {
    val resp_fire   = Input(Bool())
    val resp_bits   = Input(new ControllerResponse)
    val globalCycle = Input(UInt(64.W))
  })

  val reqId = RegInit(0.U(32.W))

  when (reset.asBool) {
    printf("output_stats.csv: RequestID,Read,Write,Cycle\n")
  }

  when (io.resp_fire) {
    printf("output_stats.csv: %d,%d,%d,%d\n",
      reqId,
      io.resp_bits.rd_en,
      io.resp_bits.wr_en,
      io.globalCycle)
    reqId := reqId + 1.U
  }
}

/** Top-level performance statistics module.
  *
  * This module “taps” both the input request and output response streams.
  * The signals:
  *   - in_fire and in_bits represent a successful (fire) input transaction.
  *   - out_fire and out_bits represent a successful (fire) output transaction.
  */
class PerformanceStatistics extends Module {
  val io = IO(new Bundle {
    val in_fire  = Input(Bool())
    val in_bits  = Input(new ControllerRequest)
    val out_fire = Input(Bool())
    val out_bits = Input(new ControllerResponse)
  })

  // Global cycle counter (64 bits)
  val cycleCounter = RegInit(0.U(64.W))
  cycleCounter := cycleCounter + 1.U

  // Instantiate tap modules.
  val perfIn  = Module(new PerformanceStatisticsInput)
  val perfOut = Module(new PerformanceStatisticsOutput)

  // Connect global cycle counter.
  perfIn.io.globalCycle  := cycleCounter
  perfOut.io.globalCycle := cycleCounter

  // Connect tap signals.
  perfIn.io.req_fire := io.in_fire
  perfIn.io.req_bits := io.in_bits

  perfOut.io.resp_fire := io.out_fire
  perfOut.io.resp_bits := io.out_bits
}
