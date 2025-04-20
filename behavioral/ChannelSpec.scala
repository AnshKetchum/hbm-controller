package memctrl

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class ChannelSpec extends AnyFreeSpec with Matchers {
  "Channel should handle basic DRAM operations" in {
    simulate(new Channel(
      MemoryConfigurationParams(),
      DRAMBankParams()
    )) { c =>
      // Helper function to enqueue a memory command
      def sendMemCmd(cs: Boolean, ras: Boolean, cas: Boolean, we: Boolean, addr: UInt, data: UInt = 0.U): Unit = {
        while (!c.io.memCmd.ready.peek().litToBoolean) { 
          c.clock.step(1) // Wait until the channel is ready to accept a command
        }
        c.io.memCmd.bits.cs.poke(cs.B)
        c.io.memCmd.bits.ras.poke(ras.B)
        c.io.memCmd.bits.cas.poke(cas.B)
        c.io.memCmd.bits.we.poke(we.B)
        c.io.memCmd.bits.addr.poke(addr)
        c.io.memCmd.bits.data.poke(data)
        c.io.memCmd.valid.poke(true.B)
        c.clock.step(1)
        c.io.memCmd.valid.poke(false.B) // Deassert valid after sending
      }

      // Helper function to wait for a valid response
      def waitForResponse(expectedData: Option[UInt] = None, maxCycles: Int = 500): Boolean = {
        var cycles = 0
        while (!c.io.phyResp.valid.peek().litToBoolean && cycles < maxCycles) {
          c.clock.step(1)
          cycles += 1
        }
        if (cycles >= maxCycles) {
          println(s"Timeout after $maxCycles cycles")
          return false
        }
        println("Received a response.")
        
        // Check data if expected
        expectedData.foreach { expected =>
          c.io.phyResp.bits.data.expect(expected, "Response data mismatch!")
        }
        
        c.io.phyResp.ready.poke(true.B) // Accept the response
        c.clock.step(1)
        c.io.phyResp.ready.poke(false.B)
        return true
      }

      // Reset initial state
      println("Resetting...")
      c.clock.step(10)

      // Test case 1: Write operation
      println("\nStarting WRITE transaction...")
      val testAddr = "hEF".U
      val testData = 420.U

      // 1. Activate row
      println("Issued activate command")
      sendMemCmd(cs = false, ras = false, cas = true, we = true, addr = testAddr)
      assert(waitForResponse(), "Activate command should complete")

      // 2. Write data
      println("Issued write command")
      sendMemCmd(cs = false, ras = true, cas = false, we = false, addr = testAddr, data = testData)
      assert(waitForResponse(), "Write command should complete")

      // 3. Precharge
      println("Issued precharge command")
      sendMemCmd(cs = false, ras = false, cas = true, we = false, addr = testAddr)
      assert(waitForResponse(), "Precharge command should complete")

      // Test case 2: Read operation
      println("\nStarting READ transaction...")

      // 1. Activate row again
      println("Issued activate command")
      sendMemCmd(cs = false, ras = false, cas = true, we = true, addr = testAddr)
      assert(waitForResponse(), "Activate command should complete")

      // 2. Read data
      println("Issued read command")
      sendMemCmd(cs = false, ras = true, cas = false, we = true, addr = testAddr)
      assert(waitForResponse(Some(testData)), "Read data should match previously written data")

      // 3. Precharge
      println("Issued precharge command")
      sendMemCmd(cs = false, ras = false, cas = true, we = false, addr = testAddr)
      assert(waitForResponse(), "Precharge command should complete")

      // Test case 3: Refresh operation
      println("\nStarting REFRESH operation...")
      sendMemCmd(cs = false, ras = false, cas = false, we = true, addr = 0.U)
      println("Issued refresh command")
      assert(waitForResponse(), "Refresh command should complete")

      println("Testbench completed successfully")
    }
  }
}
