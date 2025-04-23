// File: src/test/scala/memctrl/BankGroupSpec.scala
package memctrl

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BankGroupSpec extends AnyFreeSpec with Matchers {
  "BankGroup should handle basic DRAM operations via PhysicalMemoryIO" in {
    simulate(new BankGroup(
      MemoryConfigurationParameters(),
      DRAMBankParameters()
    )) { c =>
      // Bring simulator out of reset
      c.clock.step(5)

      // Always ready to accept responses
      c.io.phyResp.ready.poke(true.B)
      // No command valid initially
      c.io.memCmd.valid.poke(false.B)

      // Helper: enqueue one command, wait for it to be accepted (fire), then deassert valid
      def sendCmd(
        addr: UInt,
        data: UInt,
        cs: Boolean,
        ras: Boolean,
        cas: Boolean,
        we: Boolean
      ): Unit = {
        c.io.memCmd.bits.addr.poke(addr)
        c.io.memCmd.bits.data.poke(data)
        c.io.memCmd.bits.cs.poke(cs.B)
        c.io.memCmd.bits.ras.poke(ras.B)
        c.io.memCmd.bits.cas.poke(cas.B)
        c.io.memCmd.bits.we.poke(we.B)
        c.io.memCmd.valid.poke(true.B)
        // wait until the BankGroup (and its selected bank) asserts ready
        while (!c.io.memCmd.ready.peek().litToBoolean) {
          c.clock.step()
        }
        // complete the handshake
        c.clock.step()
        c.io.memCmd.valid.poke(false.B)
      }

      // Helper: wait for a response, return (addr, data)
      def expectResp(maxCycles: Int = 500): (BigInt, BigInt) = {
        var cycles = 0
        while (!c.io.phyResp.valid.peek().litToBoolean && cycles < maxCycles) {
          c.clock.step()
          cycles += 1
        }
        if (cycles >= maxCycles) {
          fail(s"No response within $maxCycles cycles")
        }
        val rAddr = c.io.phyResp.bits.addr.peek().litValue
        val rData = c.io.phyResp.bits.data.peek().litValue
        // complete response handshake
        c.clock.step()
        (rAddr, rData)
      }

      // Test parameters
      val testAddr = "hEF".U
      val testData = 420.U

      // ─── Test Case 1: WRITE ─────────────────────────────────────────────
      // 1) ACTIVATE (cs=0, ras=0, cas=1, we=1)
      sendCmd(testAddr, 0.U, cs = false, ras = false, cas = true,  we = true)
      expectResp()

      // 2) WRITE (cs=0, ras=1, cas=0, we=0)
      sendCmd(testAddr, testData, cs = false, ras = true, cas = false, we = false)
      val (wAddr, wData) = expectResp()
      wAddr mustBe testAddr.litValue
      wData mustBe testData.litValue

      // 3) PRECHARGE (cs=0, ras=0, cas=1, we=0)
      sendCmd(testAddr, 0.U, cs = false, ras = false, cas = true,  we = false)
      expectResp()

      // ─── Test Case 2: READ ──────────────────────────────────────────────
      // 1) ACTIVATE again
      sendCmd(testAddr, 0.U, cs = false, ras = false, cas = true,  we = true)
      expectResp()

      // 2) READ (cs=0, ras=1, cas=0, we=1)
      sendCmd(testAddr, 0.U, cs = false, ras = true, cas = false, we = true)
      val (rAddr, rData) = expectResp()
      rAddr mustBe testAddr.litValue
      rData mustBe testData.litValue

      // 3) PRECHARGE
      sendCmd(testAddr, 0.U, cs = false, ras = false, cas = true,  we = false)
      expectResp()

      // ─── Test Case 3: REFRESH ───────────────────────────────────────────
      // REFRESH (cs=0, ras=0, cas=0, we=1)
      sendCmd(testAddr, 0.U, cs = false, ras = false, cas = false, we = true)
      expectResp()
    }
  }
}
