// See README.md for license details.

package memctrl

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/**
  * Test suite for the MemoryController.
  *
  * The tests simulate a read and a write transaction. For a read, the test:
  *   - Sets rd_en and request_valid high with a chosen address.
  *   - Drives response_complete and response_data (e.g. "hDEADBEEF") to mimic the memory.
  *   - Waits until the done signal is asserted, then checks that the output data matches.
  *
  * Similarly, for a write, the test:
  *   - Sets wr_en and request_valid high with chosen address and write data.
  *   - Drives response_complete and response_data (e.g. "hCAFEBABE") to mimic the memory.
  *   - Waits until the done signal is asserted, then checks that the output data matches.
  */
class MemoryControllerSpec extends AnyFreeSpec with Matchers {

  "MemoryController should complete a read transaction correctly" in {
    simulate(new MemoryController(/* use default or specify parameters if needed */)) { dut =>
      // Apply reset
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.clock.step()

      // Set up a read transaction
      dut.io.rd_en.poke(true.B)
      dut.io.wr_en.poke(false.B)
      dut.io.request_valid.poke(true.B)
      dut.io.addr.poke("h1000".U)  // Example address

      // Drive memory response: for a read, assume we get the data 0xDEADBEEF
      dut.io.response_data.poke("hDEADBEEF".U)

      var cycles = 0
      // Loop until the 'done' signal is asserted or timeout (1000 cycles)
      while (!dut.io.done.peek().litToBoolean && cycles < 1000) {
        // Mimic the memory sending a response
        dut.io.response_complete.poke(true.B)
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 1000, "Timeout reached during read transaction")
      dut.io.done.expect(true.B)
      dut.io.data.expect("hDEADBEEF".U)
    }
  }

  "MemoryController should complete a write transaction correctly" in {
    simulate(new MemoryController(/* use default or specify parameters if needed */)) { dut =>
      // Apply reset
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.clock.step()

      // Set up a write transaction
      dut.io.wr_en.poke(true.B)
      dut.io.rd_en.poke(false.B)
      dut.io.request_valid.poke(true.B)
      dut.io.addr.poke("h2000".U)      // Example address
      dut.io.wdata.poke("hCAFEBABE".U) // Example write data

      // For a write, assume the memory echoes back the written data.
      dut.io.response_data.poke("hCAFEBABE".U)

      var cycles = 0
      // Loop until the 'done' signal is asserted or timeout (1000 cycles)
      while (!dut.io.done.peek().litToBoolean && cycles < 1000) {
        dut.io.response_complete.poke(true.B)
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 1000, "Timeout reached during write transaction")
      dut.io.done.expect(true.B)
      dut.io.data.expect("hCAFEBABE".U)
    }
  }
}
