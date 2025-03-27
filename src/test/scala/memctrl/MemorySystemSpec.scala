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

      // Enqueue a write transaction
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.wr_en.poke(true.B)
      dut.io.in.bits.rd_en.poke(false.B)
      dut.io.in.bits.addr.poke("h2000".U)      // Example address
      dut.io.in.bits.wdata.poke("hCAFEBABE".U) // Example write data
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      // Ensure the output side can dequeue the response
      dut.io.out.ready.poke(true.B)
      
      // Wait for write transaction to complete
      var cycles = 0
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 1000, "Timeout reached during write transaction")
      dut.io.out.valid.expect(true.B)

      // Enqueue a read transaction.
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.rd_en.poke(true.B)
      dut.io.in.bits.wr_en.poke(false.B)
      dut.io.in.bits.addr.poke("h2000".U)  // Example address
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      // Ensure the output side is still ready
      dut.io.out.ready.poke(true.B)

      // Wait for read transaction to complete
      cycles = 0
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 1000) {
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 1000, "Timeout reached during read transaction")
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data.expect("hCAFEBABE".U)
    }
  }
}
