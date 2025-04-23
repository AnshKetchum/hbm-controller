package memctrl

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class MemorySystemComplexSpec extends AnyFreeSpec with Matchers {
  "Requests should move from queue → FSM in exactly two cycles, grow active FSM count, and then drain" in {
    val numRanks      = 4
    val writesToIssue = numRanks / 2     // configurable
    val customParams  = SingleChannelMemoryConfigurationParams(
      memConfiguration  = MemoryConfigurationParameters(numberOfRanks = numRanks),
      bankConfiguration = DRAMBankParameters()
    )

    simulate(new SingleChannelSystem(customParams)) { dut =>
      // reset
      dut.reset.poke(true.B); dut.clock.step()
      dut.reset.poke(false.B); dut.clock.step()

      // prepare
      dut.io.out.ready.poke(false.B)
      dut.io.in.bits.rd_en.poke(false.B)
      dut.io.in.bits.wr_en.poke(true.B)

      // compute rankShift
      val bBits     = math.ceil(math.log(customParams.memConfiguration.numberOfBanks)/math.log(2)).toInt
      val bgBits    = math.ceil(math.log(customParams.memConfiguration.numberOfBankGroups)/math.log(2)).toInt
      val rankShift = bBits + bgBits

      // issue writes and check per-iteration
      for (i <- 0 until writesToIssue) {
        // cycle 0: poke
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.addr.poke(((i << rankShift) | 0x100).U)
        dut.io.in.bits.wdata.poke((0xA0000000L + i).U(32.W))

        // cycle 1: dequeue from top queue
        dut.clock.step()
        dut.io.in.valid.poke(false.B)

        // cycle 2: dequeue into FSM queue
        dut.clock.step()

        // cycle 3: FSM takes it (state ≠ Idle)
        dut.clock.step()

        // cycle 4: stable
        dut.clock.step()
        val state = dut.io.rankState(i).peek().litValue.toInt
        assert(state != 0, s"Write #$i still in sIdle after two cycles (state=$state)")

        // **new assertion**: total active FSMs == i+1
        val activeCount = (0 until numRanks).count { j =>
          dut.io.rankState(j).peek().litValue.toInt != 0
        }
        activeCount mustBe (i + 1)
      }

    // let system settle and track active rank count over time
    var prevActive = writesToIssue
    var cycles     = 0
    var reachedZero = false

    while (cycles < 500 && !reachedZero) {
        dut.clock.step()
        cycles += 1

        val currActive = (0 until numRanks).count { j =>
            dut.io.rankState(j).peek().litValue.toInt != 0
        }

        // Assert active count never increases
        assert(currActive <= prevActive,
            s"Active rank count increased from $prevActive to $currActive at cycle $cycles")

        // Assert active count only decreases by 1 or stays constant
        assert((prevActive - currActive) <= 1,
            s"Active rank count dropped too fast: $prevActive → $currActive at cycle $cycles")

        // Update for next check
        prevActive = currActive

        // Detect terminal condition
        if (currActive == 0) reachedZero = true
    }

    assert(reachedZero, s"Active rank count never reached zero after $cycles cycles")

    // Check the response queue count
    val respCount = dut.io.respQueueCount.peek().litValue.toInt
    assert(respCount == writesToIssue, s"Expected $writesToIssue responses, but found $respCount in the response queue")


      // now drain exactly writesToIssue responses back-to-back
      var drained = 0
      while (drained < writesToIssue) {
        // expect valid every cycle
        dut.io.out.valid.expect(true.B, s"Expected back-to-back response #$drained")
        dut.io.out.ready.poke(true.B)
        dut.clock.step()
        drained += 1
        println(s"Drained $drained")
      }
      drained mustBe writesToIssue
    }
  }
}
