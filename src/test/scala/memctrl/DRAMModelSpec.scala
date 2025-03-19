package memctrl

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class DRAMModelSpec extends AnyFreeSpec with Matchers {
  "DRAMModel should handle basic DRAM operations" in {
    simulate(new DRAMModel()) { c =>
      // Helper function to set control signals
      def setControl(cs: Int, ras: Int, cas: Int, we: Int): Unit = {
        c.io.cs.poke(cs)
        c.io.ras.poke(ras)
        c.io.cas.poke(cas)
        c.io.we.poke(we)
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
        return true
      }
      
      // Reset initial state
      println("Resetting...")
      setControl(1, 1, 1, 1)  // All signals inactive
      c.io.addr.poke(0.U)
      c.io.wdata.poke(0.U)
      c.clock.step(10)
      
      // Test case 1: Write operation
      println("\nStarting WRITE transaction...")
      val testAddr = "hDEADBEEF".U
      val testData = 69360420.U
      
      // 1. Activate row
      setControl(0, 0, 1, 1)  // Activate command
      c.io.addr.poke(testAddr)
      println("Issued activate command")
      assert(waitForComplete(), "Activate command should complete")
      
      // 2. Write data
      setControl(0, 1, 0, 0)  // Write command
      c.io.addr.poke(testAddr)
      c.io.wdata.poke(testData)
      println("Issued write command")
      assert(waitForComplete(), "Write command should complete")
      c.io.response_data.expect(testData)
      
      // 3. Precharge
      setControl(0, 0, 1, 0)  // Precharge command
      println("Issued precharge command")
      assert(waitForComplete(), "Precharge command should complete")
      
      // Test case 2: Read operation
      println("\nStarting READ transaction...")
      
      // 1. Activate row again
      setControl(0, 0, 1, 1)  // Activate command
      c.io.addr.poke(testAddr)
      println("Issued activate command")
      assert(waitForComplete(), "Activate command should complete")
      
      // 2. Read data
      setControl(0, 1, 0, 1)  // Read command
      c.io.addr.poke(testAddr)
      println("Issued read command")
      assert(waitForComplete(), "Read command should complete")
      c.io.response_data.expect(testData, "Read data should match previously written data")
      
      // 3. Precharge
      setControl(0, 0, 1, 0)  // Precharge command
      println("Issued precharge command")
      assert(waitForComplete(), "Precharge command should complete")
      
      // Test case 3: Refresh operation
      println("\nStarting REFRESH operation...")
      setControl(0, 0, 0, 1)  // Refresh command
      println("Issued refresh command")
      assert(waitForComplete(), "Refresh command should complete")
      
      println("Testbench completed successfully")
    }
  }
}