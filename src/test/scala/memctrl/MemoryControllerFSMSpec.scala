// File: src/test/scala/memctrl/MemoryControllerFSMSpec.scala
package memctrl

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MemoryControllerFSMSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  behavior of "MemControllerFSM connected to DRAMBank"

  it should "perform a write followed by a read and return correct data" in {
    test(new Module {
      val io = IO(new Bundle {
        val req  = Flipped(Decoupled(new ControllerRequest))
        val resp = Decoupled(new ControllerResponse)
      })

      val dramParams = DRAMBankParameters()

      val controller = Module(new MemoryControllerFSM(dramParams))
      val dram       = Module(new DRAMBank(dramParams))

      controller.io.req     <> io.req
      controller.io.resp    <> io.resp
      controller.io.cmdOut  <> dram.io.memCmd
      controller.io.phyResp <> dram.io.phyResp
    }) { c =>
      // === Write Request ===
      val writeAddr = 0x10.U
      val writeData = "hCAFEBABE".U

      c.io.req.valid.poke(true.B)
      c.io.req.bits.rd_en.poke(false.B)
      c.io.req.bits.wr_en.poke(true.B)
      c.io.req.bits.addr.poke(writeAddr)
      c.io.req.bits.wdata.poke(writeData)
      c.clock.step(1)
      c.io.req.valid.poke(false.B)

      // Wait for write ack
      var cycles = 0
      while (!c.io.resp.valid.peek().litToBoolean && cycles < 100) {
        c.clock.step(1)
        cycles += 1
      }
      // drive ready to complete the Decoupled handshake
      c.io.resp.ready.poke(true.B)
      c.io.resp.valid.expect(true.B)
      c.io.resp.bits.wr_en.expect(true.B)
      c.io.resp.bits.rd_en.expect(false.B)
      c.io.resp.bits.addr.expect(writeAddr)
      c.io.resp.bits.data.expect(writeData) // no data on write ack

      // consume the response
      c.clock.step(1)
      c.io.resp.ready.poke(false.B)

      // === Read Request ===
      c.io.req.valid.poke(true.B)
      c.io.req.bits.rd_en.poke(true.B)
      c.io.req.bits.wr_en.poke(false.B)
      c.io.req.bits.addr.poke(writeAddr)
      c.io.req.bits.wdata.poke(0.U)
      c.clock.step(1)
      c.io.req.valid.poke(false.B)
      
      // drive ready again
      c.io.resp.ready.poke(true.B)

      // Wait for read response
      cycles = 0
      while (!c.io.resp.valid.peek().litToBoolean && cycles < 100) {
        c.clock.step(1)
        cycles += 1
      }
      c.io.resp.valid.expect(true.B)
      c.io.resp.bits.rd_en.expect(true.B)
      c.io.resp.bits.wr_en.expect(false.B)
      c.io.resp.bits.addr.expect(writeAddr)
      c.io.resp.bits.data.expect(writeData)

      // consume the final response
      c.clock.step(1)
      c.io.resp.ready.poke(false.B)
    }
  }
}
