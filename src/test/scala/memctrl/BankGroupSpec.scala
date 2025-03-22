package memctrl

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BankGroupSpec extends AnyFreeSpec with Matchers {
  "BankGroup should handle basic DRAM operations" in {
    simulate(new BankGroup()) { c =>
      // Helper function to set control signals
      def setControl(cs: Boolean, ras: Boolean, cas: Boolean, we: Boolean): Unit = {
        c.io.cs.poke(cs.B)
        c.io.ras.poke(ras.B)
        c.io.cas.poke(cas.B)
        c.io.we.poke(we.B)
        c.clock.step(1) // Advance the clock so the new signals are latched
      }

      
      // Helper function to wait for response_complete
      def waitForComplete(maxCycles: Int = 500): Boolean = {
        var cycles = 0
        while (!c.io.response_complete.peek().litToBoolean && cycles < maxCycles) {
          c.clock.step()
          cycles += 1
        }
        if (cycles >= maxCycles) {
          println(s"Timeout after $maxCycles cycles")
          return false
        }
        println("Received a completion signal.")
        return true
      }
      
      // Reset initial state
      println("Resetting...")
      setControl(true, true, true, true)  // All signals inactive
      c.io.addr.poke(0.U)
      c.io.wdata.poke(0.U)
      c.clock.step(10)
      
      // Test case 1: Write operation
      println("\nStarting WRITE transaction...")
      val testAddr = "hEF".U
      val testData = 420.U
      
      // 1. Activate row
      println("Issued activate command")
      setControl(false, false, true, true)
      c.io.addr.poke(testAddr)
      assert(waitForComplete(), "Activate command should complete")
      c.clock.step(1)
      
      // 2. Write data
      println("Issued write command")
      setControl(false, true, false, false)  // Write command
      c.io.addr.poke(testAddr)
      c.io.wdata.poke(testData)
      assert(waitForComplete(), "Write command should complete")
      c.io.response_data.expect(testData)
      c.clock.step(1)
      
      // 3. Precharge
      println("Issued precharge command")
      setControl(false, false, true, false)  // Precharge command
      assert(waitForComplete(), "Precharge command should complete")
      c.clock.step(1)
      
      // Test case 2: Read operation
      println("\nStarting READ transaction...")
      
      // 1. Activate row again
      setControl(false, false, true, true)  // Activate command
      c.io.addr.poke(testAddr)
      println("Issued activate command")
      assert(waitForComplete(), "Activate command should complete")
      c.clock.step(1)
      
      // 2. Read data
      setControl(false, true, false, true)  // Read command
      c.io.addr.poke(testAddr)
      println("Issued read command")
      assert(waitForComplete(), "Read command should complete")
      c.io.response_data.expect(testData, "Read data should match previously written data")
      c.clock.step(1)
      
      // 3. Precharge
      setControl(false, false, true, false)  // Precharge command
      println("Issued precharge command")
      assert(waitForComplete(), "Precharge command should complete")
      c.clock.step(1)
      
      // Test case 3: Refresh operation
      println("\nStarting REFRESH operation...")
      setControl(false, false, false, true)  // Refresh command
      println("Issued refresh command")
      assert(waitForComplete(), "Refresh command should complete")
      c.clock.step(1)
      
      println("Testbench completed successfully")
    }
  }
}