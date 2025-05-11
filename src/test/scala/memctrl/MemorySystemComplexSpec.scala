package memctrl

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class MemorySystemComplexSpec extends AnyFreeSpec with Matchers {
  "Requests should move from queue → FSM in exactly two cycles, grow active FSM count, and then drain" in {
    val numRanks      = 4
    val writesToIssue = numRanks / 2
    val customParams  = SingleChannelMemoryConfigurationParams(
      memConfiguration  = MemoryConfigurationParameters(numberOfRanks = numRanks),
      bankConfiguration = DRAMBankParameters()
    )

    simulate(new SingleChannelSystem(customParams)) { dut =>
      dut.reset.poke(true.B); dut.clock.step()
      dut.reset.poke(false.B); dut.clock.step()

      dut.io.out.ready.poke(false.B)
      dut.io.in.bits.rd_en.poke(false.B)
      dut.io.in.bits.wr_en.poke(true.B)

      val bBits     = math.ceil(math.log(customParams.memConfiguration.numberOfBanks) / math.log(2)).toInt
      val bgBits    = math.ceil(math.log(customParams.memConfiguration.numberOfBankGroups) / math.log(2)).toInt
      val rankShift = bBits + bgBits

      val expected = scala.collection.mutable.ArrayBuffer[(BigInt, BigInt)]() // (addr, data)

      // Issue writes
      for (i <- 0 until writesToIssue) {

        val addr = ((i << rankShift) | 0x100).U
        val data = (0xA0000000L + i).U(32.W)
        expected += ((addr.litValue, data.litValue))

        println(f"[WRITE] Rank $i: addr = 0x${addr.litValue}%08X, data = 0x${data.litValue}%08X")

        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.addr.poke(addr)
        dut.io.in.bits.wdata.poke(data)

        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        dut.clock.step()
        dut.clock.step()
        dut.clock.step()

        val state = dut.io.rankState(i).peek().litValue.toInt
        assert(state != 0, s"Write #$i still in sIdle after two cycles (state=$state)")

        val activeCount = (0 until numRanks).count { j =>
          dut.io.rankState(j).peek().litValue.toInt != 0
        }
        activeCount mustBe (i + 1)
      }

      var prevActive = writesToIssue
      var cycles     = 0
      var reachedZero = false

      while (cycles < 1500 && !reachedZero) {
        dut.clock.step()
        cycles += 1

        val currActive = (0 until numRanks).count { j =>
          dut.io.rankState(j).peek().litValue.toInt != 0
        }

        assert(currActive <= prevActive,
          s"Active rank count increased from $prevActive to $currActive at cycle $cycles")
        assert((prevActive - currActive) <= 1,
          s"Active rank count dropped too fast: $prevActive → $currActive at cycle $cycles")

        prevActive = currActive
        if (currActive == 0) reachedZero = true
      }

      assert(reachedZero, s"Active rank count never reached zero after $cycles cycles")

      val respCount = dut.io.respQueueCount.peek().litValue.toInt
      assert(respCount == writesToIssue, s"Expected $writesToIssue responses, but found $respCount")

      // ⬇️ DRAIN LOOP: verify address + data
      val expectedSet = scala.collection.mutable.Set[(BigInt, BigInt)]() ++ expected
      val receivedSet = scala.collection.mutable.Set[(BigInt, BigInt)]()

      var drained = 0
      while (drained < writesToIssue) {
        if (dut.io.out.valid.peek().litToBoolean) {
          dut.io.out.ready.poke(true.B)

          val raddr = dut.io.out.bits.addr.peek().litValue
          val rdata = dut.io.out.bits.data.peek().litValue

          receivedSet += ((raddr, rdata))
          drained += 1
        } else {
          dut.io.out.ready.poke(false.B)
        }

        dut.clock.step()
      }

      // Ensure received responses match expected, order does not matter
      assert(receivedSet == expectedSet,
        s"[WRITE RESP] Mismatch in received responses.\nExpected: ${expectedSet.mkString(", ")}\nReceived: ${receivedSet.mkString(", ")}")

      // ⬇️ READBACK PHASE
      dut.io.in.bits.rd_en.poke(true.B)
      dut.io.in.bits.wr_en.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.clock.step()

      val expectedReadSet = scala.collection.mutable.Set[(BigInt, BigInt)]() ++ expected
      val receivedReadSet = scala.collection.mutable.Set[(BigInt, BigInt)]()

      expected.foreach { case (addr, _) =>
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.addr.poke(addr.U)

        dut.clock.step()
        dut.io.in.valid.poke(false.B)

        // Let request flow through
        dut.clock.step(); dut.clock.step(); dut.clock.step(); dut.clock.step()
      }

      var waitCycles = 0
      while (receivedReadSet.size < expectedReadSet.size && waitCycles < 500) {
        if (dut.io.out.valid.peek().litToBoolean) {
          dut.io.out.ready.poke(true.B)

          val raddr = dut.io.out.bits.addr.peek().litValue
          val rdata = dut.io.out.bits.data.peek().litValue

          receivedReadSet += ((raddr, rdata))
        } else {
          dut.io.out.ready.poke(false.B)
        }

        dut.clock.step()
        waitCycles += 1
      }

      // Check if the readback responses match the expected ones
      assert(receivedReadSet == expectedReadSet,
        s"[READBACK] Mismatch in received responses.\nExpected: ${expectedReadSet.mkString(", ")}\nReceived: ${receivedReadSet.mkString(", ")}")
    }
  }
}