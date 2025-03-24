// See README.md for license details.

package memctrl

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/**
  * Test suite for the MemoryController with decoupled request and response interfaces.
  *
  * For a read transaction, the test:
  *   - Enqueues a read request on the 'in' interface.
  *   - Drives memResp_valid and memResp_data (e.g. "hDEADBEEF") to mimic the memory.
  *   - Waits until a response appears on the 'out' interface, then checks that the response data matches.
  *
  * For a write transaction, the test:
  *   - Enqueues a write request on the 'in' interface.
  *   - Drives memResp_valid and memResp_data (e.g. "hCAFEBABE") to mimic the memory.
  *   - Waits until a response appears on the 'out' interface, then checks that the response data matches.
  */
class MemoryControllerSpec extends AnyFreeSpec with Matchers {

  "MemoryController should complete a read transaction correctly" in {
    simulate(new MemoryController(/* use default or specify parameters if needed */)) { dut =>
      // Apply reset
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.clock.step()

      // Enqueue a read transaction.
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.rd_en.poke(true.B)
      dut.io.in.bits.wr_en.poke(false.B)
      dut.io.in.bits.addr.poke("h1000".U)  // Example address
      // For a read, wdata is don't care (set to 0)
      dut.io.in.bits.wdata.poke(0.U)
      // Allow the request to be enqueued.
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      // Drive memory response: for a read, assume we get the data 0xDEADBEEF.
      dut.io.memResp_data.poke("hDEADBEEF".U)

      var cycles = 0
      // Set out.ready to true so that the response queue can dequeue the response.
      dut.io.out.ready.poke(true.B)
      // Loop until the response appears on the out interface or timeout (1000 cycles)
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
        // Mimic the memory sending a response.
        dut.io.memResp_valid.poke(true.B)
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 1000, "Timeout reached during read transaction")
      dut.io.out.valid.expect(true.B)
      // Check that the returned data matches the expected read data.
      dut.io.out.bits.data.expect("hDEADBEEF".U)
    }
  }

  "MemoryController should complete a write transaction correctly" in {
    simulate(new MemoryController(/* use default or specify parameters if needed */)) { dut =>
      // Apply reset
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.clock.step()

      // Enqueue a write transaction.
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.wr_en.poke(true.B)
      dut.io.in.bits.rd_en.poke(false.B)
      dut.io.in.bits.addr.poke("h2000".U)      // Example address
      dut.io.in.bits.wdata.poke("hCAFEBABE".U) // Example write data
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      // For a write, assume the memory echoes back the written data.
      dut.io.memResp_data.poke("hCAFEBABE".U)

      var cycles = 0
      // Set out.ready to true so that the response queue can dequeue the response.
      dut.io.out.ready.poke(true.B)
      // Loop until the response appears on the out interface or timeout (1000 cycles)
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
        dut.io.memResp_valid.poke(true.B)
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 1000, "Timeout reached during write transaction")
      dut.io.out.valid.expect(true.B)
      // Check that the returned data matches the expected write data.
      dut.io.out.bits.data.expect("hCAFEBABE".U)
    }
  }
}
