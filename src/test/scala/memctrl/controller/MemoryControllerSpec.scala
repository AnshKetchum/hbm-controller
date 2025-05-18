// See README.md for license details.

package memctrl

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** Test suite for the MultiRankMemoryController with decoupled interfaces.
  *
  * For a read transaction:
  *   - Enqueues a read request on the 'in' interface.
  *   - Drives the physical memory response (phyResp) with a matching address and data (e.g. "hDEADBEEF").
  *   - Waits until a response appears on the 'out' interface, then checks that the response data matches.
  *
  * For a write transaction:
  *   - Enqueues a write request on the 'in' interface.
  *   - Drives the physical memory response (phyResp) with a matching address and data (e.g. "hCAFEBABE").
  *   - Waits until a response appears on the 'out' interface, then checks that the response data matches.
  */
class MultiRankMemoryControllerSpec extends AnyFreeSpec with Matchers {

  "MultiRankMemoryController should complete a read transaction correctly" in {
    simulate(
      new MultiRankMemoryController(
        MemoryConfigurationParameters(),
        DRAMBankParameters(),
        MemoryControllerParameters(),
        LocalConfigurationParameters()
      )
    ) { dut =>
      // Apply reset
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.clock.step()

      // Enqueue a read transaction.
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.rd_en.poke(true.B)
      dut.io.in.bits.wr_en.poke(false.B)
      dut.io.in.bits.addr.poke("h1000".U) // Example address
      // For a read, wdata is don't care (set to 0)
      dut.io.in.bits.wdata.poke(0.U)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      // Set out.ready so that responses can be dequeued.
      dut.io.out.ready.poke(true.B)

      // Mimic the memory sending a response on the phyResp channel.
      // Note: The MultiRankMemoryController expects the phyResp.bits.addr to match the issued request.
      dut.io.phyResp.bits.addr.poke("h1000".U)
      dut.io.phyResp.bits.data.poke("hDEADBEEF".U)

      var cycles = 0
      // Keep driving phyResp.valid true until a response is available or timeout.
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
        dut.io.phyResp.valid.poke(true.B)
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 1000, "Timeout reached during read transaction")
      dut.io.out.valid.expect(true.B)
      // Check that the returned data matches the expected read data.
      dut.io.out.bits.data.expect("hDEADBEEF".U)
    }
  }

  "MultiRankMemoryController should complete a write transaction correctly" in {
    simulate(
      new MultiRankMemoryController(
        MemoryConfigurationParameters(),
        DRAMBankParameters(),
        MemoryControllerParameters(),
        LocalConfigurationParameters()
      )
    ) { dut =>
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

      // Set out.ready so that responses can be dequeued.
      dut.io.out.ready.poke(true.B)

      // Mimic the memory echoing back the write data via the phyResp channel.
      dut.io.phyResp.bits.addr.poke("h2000".U)
      dut.io.phyResp.bits.data.poke("hCAFEBABE".U)

      var cycles = 0
      // Keep driving phyResp.valid true until the response appears.
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
        dut.io.phyResp.valid.poke(true.B)
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
