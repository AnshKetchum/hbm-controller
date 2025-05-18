/** Verification Spec Target:
  *
  * \- Verify, at the functional (input / output) level that Physical memory modules work as desired (i.e, we can issue
  * reads and writes) \- Verify that the FSM can drive ANY and ALL Physical DRAM Memory Instances
  */

package memctrl

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class PhysicalMemoryModuleSpec extends AnyFreeSpec with Matchers {

  // -----------------------
  // DRAM Flow Test Helpers
  // -----------------------
  private def sendCmd(
    dut:  PhysicalMemoryModuleBase,
    addr: UInt,
    data: UInt,
    cs:   Boolean,
    ras:  Boolean,
    cas:  Boolean,
    we:   Boolean
  ): Unit = {
    dut.io.memCmd.bits.addr.poke(addr)
    dut.io.memCmd.bits.data.poke(data)
    dut.io.memCmd.bits.cs.poke(cs.B)
    dut.io.memCmd.bits.ras.poke(ras.B)
    dut.io.memCmd.bits.cas.poke(cas.B)
    dut.io.memCmd.bits.we.poke(we.B)
    dut.io.memCmd.valid.poke(true.B)
    while (!dut.io.memCmd.ready.peek().litToBoolean) dut.clock.step()
    dut.clock.step()
    dut.io.memCmd.valid.poke(false.B)
  }

  private def expectResp(dut: PhysicalMemoryModuleBase, expAddr: UInt, expData: UInt, maxCycles: Int = 500): Unit = {
    var cycles = 0
    while (!dut.io.phyResp.valid.peek().litToBoolean && cycles < maxCycles) {
      dut.clock.step(); cycles += 1
    }
    assert(cycles < maxCycles, s"Timed out after $maxCycles cycles waiting for response")
    dut.io.phyResp.valid.expect(true.B)
    dut.io.phyResp.bits.addr.expect(expAddr)
    dut.io.phyResp.bits.data.expect(expData)
    dut.clock.step()
  }

  private def dramFlowSpec(name: String, instantiate: => PhysicalMemoryModuleBase): Unit = {
    s"$name DRAM Flow" - {
      "should support activate → write → read → precharge → refresh" in {
        simulate(instantiate) { dut =>
          dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B); dut.clock.step()
          dut.io.phyResp.ready.poke(true.B)
          val base = 0x10.U; val pat = "hABCD".U
          // init read

          println("IN DRAM FLOW SPEC")
          sendCmd(dut, base, 0.U, cs = false, ras = false, cas = true, we = true)
          expectResp(dut, base, 0.U)
          sendCmd(dut, base, 0.U, cs = false, ras = true, cas = false, we = true)
          expectResp(dut, base, 0.U)
          sendCmd(dut, base, 0.U, cs = false, ras = false, cas = true, we = false)
          expectResp(dut, base, 0.U)
          // write pat
          sendCmd(dut, base, 0.U, cs = false, ras = false, cas = true, we = true)
          expectResp(dut, base, 0.U)
          sendCmd(dut, base, pat, cs = false, ras = true, cas = false, we = false)
          expectResp(dut, base, pat)
          sendCmd(dut, base, 0.U, cs = false, ras = false, cas = true, we = false)
          expectResp(dut, base, 0.U)
          // refresh
          sendCmd(dut, base, 0.U, cs = false, ras = false, cas = false, we = true)
          expectResp(dut, base, 0.U)
        }
      }
    }
  }

  // ----------------
  // Test Invocation
  // DRAM flow tests
  val memParams   = MemoryConfigurationParameters()
  val bankParams  = DRAMBankParameters()
  val localConfig = LocalConfigurationParameters(
    channelIndex = 0,
    rankIndex = 0,
    bankIndex = 0
  )

  println("[PhysicalMemorySpec] In here. ")
  dramFlowSpec("Channel", new Channel(memParams, bankParams, localConfig))
  dramFlowSpec("Rank", new Rank(memParams, bankParams, localConfig))
}
