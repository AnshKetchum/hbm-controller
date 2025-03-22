package memctrl

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

// A top-level module that wires together the MemoryController and DRAMModel.
class MemorySystem extends Module {
  val io = IO(new Bundle {
    // User interface signals
    val wr_en         = Input(Bool())
    val rd_en         = Input(Bool())
    val addr          = Input(UInt(32.W))
    val wdata         = Input(UInt(32.W))
    val request_valid = Input(Bool())
    // User outputs
    val data          = Output(UInt(32.W))
    val done          = Output(Bool())
  })

  // Instantiate the memory controller and the DRAM model.
  val memctrl = Module(new MemoryController())
  val dram    = Module(new DRAMModel())

  // Connect the controller to the DRAM.
  dram.io.cs   := memctrl.io.cs
  dram.io.ras  := memctrl.io.ras
  dram.io.cas  := memctrl.io.cas
  dram.io.we   := memctrl.io.we
  dram.io.addr := memctrl.io.request_addr  // address comes from the controller
  dram.io.wdata:= memctrl.io.request_data   // write data from the controller

  // Connect the DRAM responses back to the controller.
  memctrl.io.response_complete := dram.io.response_complete
  memctrl.io.response_data     := dram.io.response_data

  // Connect the user interface.
  memctrl.io.wr_en         := io.wr_en
  memctrl.io.rd_en         := io.rd_en
  memctrl.io.addr          := io.addr
  memctrl.io.wdata         := io.wdata
  memctrl.io.request_valid := io.request_valid

  io.data := memctrl.io.data
  io.done := memctrl.io.done
}

// Verification spec for the MemorySystem
class MemorySystemSpec extends AnyFreeSpec with Matchers {
  "MemorySystem should correctly handle write, read, and refresh transactions" in {
    simulate(new MemorySystem()) { c =>
      // Helper function: wait until the MemoryController signals done.
      def waitForDone(maxCycles: Int = 500): Boolean = {
        var cycles = 0
        while (!c.io.done.peek().litToBoolean && cycles < maxCycles) {
          c.clock.step()
          cycles += 1
        }
        if (cycles >= maxCycles) {
          println(s"Timeout after $maxCycles cycles")
          return false
        }
        println("Transaction completed.")
        true
      }

      // Reset initial state.
      println("Resetting MemorySystem...")
      c.io.wr_en.poke(false.B)
      c.io.rd_en.poke(false.B)
      c.io.request_valid.poke(false.B)
      c.io.addr.poke(0.U)
      c.io.wdata.poke(0.U)
      c.clock.step(10)

      // --- Test Case 1: Write Transaction ---
      println("\nStarting WRITE transaction...")
      val testAddr = "hEF".U      // example address
      val testData = 420.U        // example data value

      // Issue a write request.
      c.io.addr.poke(testAddr)
      c.io.wdata.poke(testData)
      c.io.wr_en.poke(true.B)
      c.io.request_valid.poke(true.B)
      c.clock.step(1)

      // Wait for the write transaction to complete.
      assert(waitForDone(), "Write transaction did not complete")
      c.io.wr_en.poke(false.B)
      c.io.request_valid.poke(false.B)
      c.clock.step(5)

      // --- Test Case 2: Read Transaction ---
      println("\nStarting READ transaction...")
      // Issue a read request.
      c.io.addr.poke(testAddr)
      c.io.rd_en.poke(true.B)
      c.io.request_valid.poke(true.B)
      c.clock.step(1)

      // Wait for the read transaction to complete.
      assert(waitForDone(), "Read transaction did not complete")
      // Check that the data read matches the previously written data.
      c.io.data.expect(testData, "Read data should match written data")
      c.io.rd_en.poke(false.B)
      c.io.request_valid.poke(false.B)
      c.clock.step(5)

      // --- Test Case 3: Refresh Operation ---
      println("\nStarting REFRESH operation...")
      // The MemoryController will trigger a refresh when its internal refresh delay counter reaches the threshold.
      // In this test, we simply wait enough cycles to allow the refresh to occur.
      // (For default parameters, REFRESH_CYCLE_COUNT is 200 cycles.)
      c.clock.step(210)
      // Optionally, issue a dummy transaction or check internal signals to ensure refresh behavior.
      // Here, we simply print that we waited for the refresh to occur.

      println("Testbench completed successfully")
    }
  }
}
