package memctrl

import chisel3._
import chisel3.util._
import chisel3.experimental.{annotate, ChiselAnnotation}
import chiseltest._
import chiseltest.formal._
import org.scalatest.flatspec.AnyFlatSpec
import chiseltest.formal.FormalTag

/**
  * Formal verification spec for the SingleChannelSystem.
  *
  * This spec instantiates the DUT, forces its output to be always ready, and constrains the
  * input interface to never issue simultaneous write and read commands. It captures the first write
  * transaction (address and data) and then the first read transaction (to the same address).
  * When a valid output is produced, an assertion checks that the returned data matches the data written.
  * A cover property is also provided.
  */
class SingleChannelMemoryFormalSpec extends AnyFlatSpec with ChiselScalatestTester with Formal {
  "SingleChannelSystem" should "correctly handle a write followed by a read transaction" in {
    verify(new SingleChannelSystem, Seq(BoundedCheck(20), DefaultBackend))
  }
}

class SingleChannelSystemFormal extends Module {
  val io = IO(new MemorySystemIO())

  // In formal mode, force the output to always be ready.
  io.out.ready := true.B

  // Registers to track the first write transaction and a subsequent read.
  val writeOccurred = RegInit(false.B)
  val readOccurred  = RegInit(false.B)
  val storedAddr    = Reg(UInt(32.W))
  val storedData    = Reg(UInt(32.W))

  // Capture the first valid write transaction.
  when(io.in.valid && io.in.bits.wr_en && !writeOccurred) {
    writeOccurred := true.B
    storedAddr    := io.in.bits.addr
    storedData    := io.in.bits.wdata
  }

  // When a valid read occurs for the stored address, record that a read transaction happened.
  when(io.in.valid && io.in.bits.rd_en && writeOccurred &&
       (io.in.bits.addr === storedAddr) && !readOccurred) {
    readOccurred := true.B
  }

  // Once a read is in progress and the output is valid, assert that the returned data matches the stored data.
  when(readOccurred && io.out.valid) {
    assert(io.out.bits.data === storedData, "Read data must match written data")
  }

  // Cover property: help the formal tool see that a write-read sequence with valid output is reachable.
  cover(writeOccurred && readOccurred && io.out.valid)
}
