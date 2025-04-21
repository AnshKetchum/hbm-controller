// File: src/test/scala/memctrl/ChannelSpec.scala
package memctrl

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class ChannelSpec extends AnyFreeSpec with Matchers {
  "Channel should handle basic DRAM operations via Decoupled I/O" in {
    simulate(new Channel(
      MemoryConfigurationParameters(),
      DRAMBankParameters()
    )) { c =>
      // Initial setup
      c.clock.step(10)  // let things settle
      c.io.phyResp.ready.poke(true.B)
      c.io.memCmd.valid.poke(false.B)

      // Helpers
      def stepUntil(cond: => Boolean, maxCycles: Int = 500): Boolean = {
        var cycles = 0
        while (!cond && cycles < maxCycles) {
          c.clock.step()
          cycles += 1
        }
        if (cycles >= maxCycles) {
          println(s"[timeout] Waited $maxCycles cycles, condition not met.")
          false
        } else {
          true
        }
      }

      def sendMemCmd(cs: Boolean, ras: Boolean, cas: Boolean, we: Boolean, addr: UInt, data: UInt = 0.U): Unit = {
        // Wait until the channel is ready
        val ready = stepUntil(c.io.memCmd.ready.peek().litToBoolean)
        assert(ready, "Timeout waiting for memCmd.ready")

        c.io.memCmd.bits.cs.poke(cs.B)
        c.io.memCmd.bits.ras.poke(ras.B)
        c.io.memCmd.bits.cas.poke(cas.B)
        c.io.memCmd.bits.we.poke(we.B)
        c.io.memCmd.bits.addr.poke(addr)
        c.io.memCmd.bits.data.poke(data)
        c.io.memCmd.valid.poke(true.B)
        c.clock.step()
        c.io.memCmd.valid.poke(false.B)
      }

      def expectResponse(expected: Option[UInt] = None): Unit = {
        val ok = stepUntil(c.io.phyResp.valid.peek().litToBoolean)
        assert(ok, "Expected response not received in time")

        expected.foreach { data =>
          c.io.phyResp.bits.data.expect(data, s"Expected response data $data")
        }

        c.io.phyResp.ready.poke(true.B)
        c.clock.step()
        c.io.phyResp.ready.poke(false.B)
      }

      // Test constants
      val testAddr = "hEF".U
      val testData = 420.U

      // ─── Test Case 1: WRITE ─────────────────────────────────────────────
      println("[WRITE] Activating row (ACTIVATE)...")
      sendMemCmd(cs = false, ras = false, cas = true, we = true, addr = testAddr)
      expectResponse()

      println("[WRITE] Sending WRITE...")
      sendMemCmd(cs = false, ras = true, cas = false, we = false, addr = testAddr, data = testData)
      expectResponse()

      println("[WRITE] Precharging bank...")
      sendMemCmd(cs = false, ras = false, cas = true, we = false, addr = testAddr)
      expectResponse()

      // ─── Test Case 2: READ ──────────────────────────────────────────────
      println("[READ] Activating row (ACTIVATE)...")
      sendMemCmd(cs = false, ras = false, cas = true, we = true, addr = testAddr)
      expectResponse()

      println("[READ] Sending READ...")
      sendMemCmd(cs = false, ras = true, cas = false, we = true, addr = testAddr)
      expectResponse(Some(testData))

      println("[READ] Precharging bank...")
      sendMemCmd(cs = false, ras = false, cas = true, we = false, addr = testAddr)
      expectResponse()

      // ─── Test Case 3: REFRESH ───────────────────────────────────────────
      println("[REFRESH] Issuing refresh command...")
      sendMemCmd(cs = false, ras = false, cas = false, we = true, addr = 0.U)
      expectResponse()

      println("✅ All Channel test cases passed.")
    }
  }
}
