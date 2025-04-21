package memctrl

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class RankSpec extends AnyFreeSpec with Matchers {
  "Rank should handle basic DRAM operations via Decoupled I/O" in {
    simulate(new Rank(
      MemoryConfigurationParameters(),
      DRAMBankParameters()
    )) { c =>
      // Let reset settle
      c.clock.step(5)

      // Always ready to accept responses
      c.io.phyResp.ready.poke(true.B)
      c.io.memCmd.valid.poke(false.B)

      // Helper: enqueue one command and wait until it's accepted (cmd.fire)
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

        println("Waiting for ready from the physical memory side")
        while (!c.io.memCmd.ready.peek().litToBoolean) {
          c.clock.step()
          println("Pending ready from the physical memory side")
        }
        println("Received ready from the physical memory side")
        c.clock.step()
        c.io.memCmd.valid.poke(false.B)
      }

      // Helper: wait for a response and return (addr, data)
      def expectResp(maxCycles: Int = 500): (BigInt, BigInt) = {
        var cycles = 0
        while (!c.io.phyResp.valid.peek().litToBoolean && cycles < maxCycles) {
          println("Pending response")
          c.clock.step()
          cycles += 1
        }
        if (cycles >= maxCycles) {
          fail(s"No response within $maxCycles cycles")
        }
        val gotAddr = c.io.phyResp.bits.addr.peek().litValue
        val gotData = c.io.phyResp.bits.data.peek().litValue
        c.clock.step()
        (gotAddr, gotData)
      }

      val testAddr = "hEF".U
      val testData = 420.U

      // ─── Test Case 1: WRITE ─────────────────────────────────────────────
      println("Sending ACTIVATE for WRITE")
      sendCmd(testAddr, 0.U, cs = false, ras = false, cas = true, we = true)
      expectResp()

      println("Issuing WRITE")
      sendCmd(testAddr, testData, cs = false, ras = true, cas = false, we = false)
      val (wAddr, wData) = expectResp()
      wAddr mustBe testAddr.litValue
      wData mustBe testData.litValue

      println("Issuing PRECHARGE after WRITE")
      sendCmd(testAddr, 0.U, cs = false, ras = false, cas = true, we = false)
      expectResp()

      // ─── Test Case 2: READ ──────────────────────────────────────────────
      println("Sending ACTIVATE for READ")
      sendCmd(testAddr, 0.U, cs = false, ras = false, cas = true, we = true)
      expectResp()

      println("Issuing READ")
      sendCmd(testAddr, 0.U, cs = false, ras = true, cas = false, we = true)
      val (rAddr, rData) = expectResp()
      rAddr mustBe testAddr.litValue
      rData mustBe testData.litValue

      println("Issuing PRECHARGE after READ")
      sendCmd(testAddr, 0.U, cs = false, ras = false, cas = true, we = false)
      expectResp()

      // ─── Test Case 3: REFRESH ───────────────────────────────────────────
      println("Issuing REFRESH")
      sendCmd(testAddr, 0.U, cs = false, ras = false, cas = false, we = true)
      expectResp()
    }
  }
}
