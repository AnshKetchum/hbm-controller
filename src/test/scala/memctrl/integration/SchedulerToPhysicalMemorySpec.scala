package memctrl

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class MemoryControllerIntegrationSpec extends AnyFreeSpec with Matchers {

  private def controllerFlowSpec(name: String, instantiateMem: => PhysicalMemoryModuleBase): Unit = {
    s"MemControllerFSM + $name" - {
      "should perform write/read/write/read sequences" in {
        simulate(new Module {
          val io = IO(new Bundle {
            val req  = Flipped(Decoupled(new ControllerRequest))
            val resp = Decoupled(new ControllerResponse)
          })

          val params      = DRAMBankParameters()
          val localConfig = LocalConfigurationParameters(
            channelIndex = 0,
            rankIndex = 0,
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
          dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B); dut.clock.step()
          dut.io.resp.ready.poke(true.B)

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

          val addr0 = "h00".U
          val addr1 = "h40".U
          val d0    = "hAAAA".U
          val d1    = "h5555".U

          println("Test 1")
          sendReq(rd = false, wr = true, addr0, d0)
          expectRespCR(rd = false, wr = true, addr0, d0)

          println("Test 2")
          sendReq(rd = true, wr = false, addr0, 0.U)
          expectRespCR(rd = true, wr = false, addr0, d0)

          println("Test 3")
          sendReq(false, true, addr1, d1)
          expectRespCR(false, true, addr1, d1)

          println("Test 4")
          sendReq(true, false, addr1, 0.U)
          expectRespCR(true, false, addr1, d1)
        }
      }
    }
  }

  // ----------------
  // Test Invocation
  val memParams   = MemoryConfigurationParameters()
  val bankParams  = DRAMBankParameters()
  val localConfig = LocalConfigurationParameters(
    channelIndex = 0,
    rankIndex = 0,
    bankIndex = 0
  )

  controllerFlowSpec("Channel", new Channel(memParams, bankParams))
  controllerFlowSpec("Rank", new Rank(memParams, bankParams, localConfig))
}
