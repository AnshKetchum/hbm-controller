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

  // -----------------------------
  // Controller Integration Test
  // -----------------------------
  // -----------------------------
  // Controller Integration Test (inlined wiring)
  // -----------------------------
  private def controllerFlowSpec(name: String, instantiateMem: => PhysicalMemoryModuleBase): Unit = {
    s"MemControllerFSM + $name" - {
      "should perform write/read/write/read sequences" in {
        simulate(new Module {
          val io     = IO(new Bundle {
            val req  = Flipped(Decoupled(new ControllerRequest))
            val resp = Decoupled(new ControllerResponse)
          })
          val params = DRAMBankParameters()

          val localConfig = LocalConfigurationParameters(
            channelIndex = 0,
            rankIndex = 0,
            bankGroupIndex = 0,
            bankIndex = 0
          )
          val memParams   = MemoryConfigurationParameters()

          val controller = Module(new MemoryControllerFSM(params, localConfig, memParams))
          val phys       = Module(instantiateMem)
          controller.io.req <> io.req
          controller.io.resp <> io.resp
          controller.io.cmdOut <> phys.io.memCmd
          controller.io.phyResp <> phys.io.phyResp
        }) { dut =>
          // release reset
          dut.reset.poke(true.B)
          dut.clock.step()
          dut.reset.poke(false.B)
          dut.clock.step()

          // ready to accept responses
          dut.io.resp.ready.poke(true.B)

          // Helpers
          def sendReq(rd: Boolean, wr: Boolean, addr: UInt, data: UInt): Unit = {
            dut.io.req.valid.poke(true.B)
            dut.io.req.bits.rd_en.poke(rd.B)
            dut.io.req.bits.wr_en.poke(wr.B)
            dut.io.req.bits.addr.poke(addr)
            dut.io.req.bits.wdata.poke(data)
            dut.clock.step()
            dut.io.req.valid.poke(false.B)
          }

          def expectRespCR(rd: Boolean, wr: Boolean, addr: UInt, data: UInt, maxCycles: Int = 500): Unit = {
            var cycles = 0
            while (!dut.io.resp.valid.peek().litToBoolean && cycles < maxCycles) {
              dut.clock.step()
              cycles += 1
            }
            assert(cycles < maxCycles, s"Timeout on controller for $name after $cycles cycles")
            dut.io.resp.valid.expect(true.B)
            dut.io.resp.bits.rd_en.expect(rd.B)
            dut.io.resp.bits.wr_en.expect(wr.B)
            dut.io.resp.bits.addr.expect(addr)
            dut.io.resp.bits.data.expect(data)
            dut.clock.step()
          }

          // Test sequence with valid addresses for rank 0, bg 0, bank 0
          val addr0 = "h00".U
          val addr1 = "h40".U
          val d0    = "hAAAA".U
          val d1    = "h5555".U

          // write d0 at addr0
          println("Test 1")
          sendReq(rd = false, wr = true, addr0, d0)
          expectRespCR(rd = false, wr = true, addr0, d0)

          // read back d0
          println("Test 2")
          sendReq(rd = true, wr = false, addr0, 0.U)
          expectRespCR(rd = true, wr = false, addr0, d0)

          // write d1 at addr1
          println("Test 3")
          sendReq(false, true, addr1, d1)
          expectRespCR(false, true, addr1, d1)

          // read back d1
          println("Test 4")
          sendReq(true, false, addr1, 0.U)
          expectRespCR(true, false, addr1, d1)
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
    bankGroupIndex = 0,
    bankIndex = 0
  )

  println("[PhysicalMemorySpec] In here. ")
  dramFlowSpec("Channel", new Channel(memParams, bankParams))
  dramFlowSpec("Rank", new Rank(memParams, bankParams, localConfig))

  // Controller integration tests
  controllerFlowSpec("Channel", new Channel(memParams, bankParams))
  controllerFlowSpec("Rank", new Rank(memParams, bankParams, localConfig))
}
