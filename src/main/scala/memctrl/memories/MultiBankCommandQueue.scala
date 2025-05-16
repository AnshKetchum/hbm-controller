package memctrl

import chisel3._
import chisel3.util._

/** Demultiplexes a PhysicalMemoryCommand into per-bank subqueues based on AddressDecoder.io.bankIndex.
  */
class MultiBankCmdQueue(
  params:   MemoryConfigurationParameters,
  numBanks: Int,
  depth:    Int)
    extends Module {
  val io = IO(new Bundle {
    val enq    = Flipped(Decoupled(new PhysicalMemoryCommand))
    val deq    = Vec(numBanks, Decoupled(new PhysicalMemoryCommand))
    val counts = Output(Vec(numBanks, UInt(log2Ceil(depth + 1).W)))
  })

  // Decode bank index
  val addrDec = Module(new AddressDecoder(params))
  addrDec.io.addr := io.enq.bits.addr
  val bankIdx = addrDec.io.bankIndex

  // One queue per bank
  val queues = Seq.fill(numBanks) {
    Module(new Queue(new PhysicalMemoryCommand, entries = depth))
  }

  // Default wiring: hook up dequeues & counts, hold off enq
  for ((q, i) <- queues.zipWithIndex) {
    io.deq(i) <> q.io.deq
    io.counts(i)   := q.io.count
    q.io.enq.bits  := io.enq.bits
    q.io.enq.valid := false.B
  }

  // Only enqueue to selected bank queue
  for ((q, i) <- queues.zipWithIndex) {
    when(bankIdx === i.U) {
      q.io.enq.valid := io.enq.valid
    }
  }

  // Ready when selected queue ready
  io.enq.ready := queues.zipWithIndex.map { case (q, i) => (bankIdx === i.U) && q.io.enq.ready }
    .reduce(_ || _)
}
