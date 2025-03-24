package memctrl

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/**
  * Verification spec for a SingleChannelSystem with decoupled user interfaces.
  *
  * The test enqueues a write request followed by a read request on the 'in' interface.
  * The response from the 'out' interface is used to verify that the written data can be read back.
  */
class SingleChannelMemorySystemSpec extends AnyFreeSpec with Matchers {

  "SingleChannelSystem should correctly handle a write followed by a read transaction" in {
    simulate(new SingleChannelSystem()) { dut =>
      
      // Apply reset
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.clock.step()

      // Enqueue a read transaction
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.wr_en.poke(true.B)
      dut.io.in.bits.rd_en.poke(false.B)
      dut.io.in.bits.addr.poke("h2000".U)      // Example address
      dut.io.in.bits.wdata.poke("hCAFEBABE".U) // Example write data
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      var cycles = 0
      // Set out.ready to true so that the response queue can dequeue the response.
      dut.io.out.ready.poke(true.B)
      // Loop until the response appears on the out interface or timeout (1000 cycles)
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 1000, "Timeout reached during write transaction")
      dut.io.out.valid.expect(true.B)
      // Check that the returned data matches the expected write data.
      dut.io.out.bits.data.expect("hCAFEBABE".U) 

      // Enqueue a read transaction.
      println("\n\nStarting READ transaction.")
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.rd_en.poke(true.B)
      dut.io.in.bits.wr_en.poke(false.B)
      dut.io.in.bits.addr.poke("h2000".U)  // Example address
      // For a read, wdata is don't care (set to 0)
      // dut.io.in.bits.wdata.poke(0.U)
      // Allow the request to be enqueued.
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      cycles = 0
      // Set out.ready to true so that the response queue can dequeue the response.
      dut.io.out.ready.poke(true.B)
      // Loop until the response appears on the out interface or timeout (1000 cycles)
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 1000, "Timeout reached during read transaction")
      dut.io.out.valid.expect(true.B)
      // Check that the returned data matches the expected read data.
      dut.io.out.bits.data.expect("hCAFEBABE".U)
    }
  }
}
