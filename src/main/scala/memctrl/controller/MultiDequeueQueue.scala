package memctrl

import chisel3._
import chisel3.util._

/** A multi-dequeue FIFO that uses AddressDecoder to steer each request into the correct per-bank subqueue.
  */
class MultiDeqQueue(
  params:        MemoryConfigurationParameters,
  banksPerRank:  Int,
  totalBankFSMs: Int,
  depth:         Int)
    extends Module {
  val io = IO(new Bundle {
    val enq    = Flipped(Decoupled(new ControllerRequest))
    val deq    = Vec(totalBankFSMs, Decoupled(new ControllerRequest))
    val counts = Output(Vec(totalBankFSMs, UInt(log2Ceil(depth + 1).W)))
  })

  // Address decoder for the incoming enqueue bits
  val addrDec = Module(new AddressDecoder(params))
  addrDec.io.addr := io.enq.bits.addr

  // Compute flat index: rank * banksPerRank + group * banks + bank
  val flatIdx = addrDec.io.rankIndex * banksPerRank.U +
    addrDec.io.bankGroupIndex * params.numberOfBanks.U +
    addrDec.io.bankIndex

  // Instantiate one Queue per FSM
  val queues = Seq.fill(totalBankFSMs) {
    Module(new Queue(new ControllerRequest, entries = depth))
  }

  // Default wiring
  for ((q, i) <- queues.zipWithIndex) {
    // dequeue side goes to output
    io.deq(i).bits  := q.io.deq.bits
    io.deq(i).valid := q.io.deq.valid
    q.io.deq.ready  := io.deq(i).ready

    // expose counts
    io.counts(i) := q.io.count

    // default: don't enqueue
    q.io.enq.bits  := io.enq.bits
    q.io.enq.valid := false.B
  }

  // Only enqueue into the queue matching flatIdx
  for ((q, i) <- queues.zipWithIndex) {
    when(flatIdx === i.U) {
      q.io.enq.valid := io.enq.valid
    }
  }

  // Input ready when the selected sub-queue can accept
  io.enq.ready := queues.zipWithIndex.map { case (q, i) =>
    (flatIdx === i.U) && q.io.enq.ready
  }.reduce(_ || _)
}
