package memctrl

import chisel3._
import chisel3.util._

// Demultiplexes a PhysicalMemoryCommand into per‐rank subqueues
class MultiRankCmdQueue(
  params: MemoryConfigurationParameters,
  numRanks: Int,
  depth: Int
) extends Module {
  val io = IO(new Bundle {
    val enq    = Flipped(Decoupled(new PhysicalMemoryCommand))
    val deq    = Vec(numRanks, Decoupled(new PhysicalMemoryCommand))
    val counts = Output(Vec(numRanks, UInt(log2Ceil(depth+1).W)))
  })

  // 1) Decode rank
  val addrDec   = Module(new AddressDecoder(params))
  addrDec.io.addr := io.enq.bits.addr
  val rankIdx   = addrDec.io.rankIndex

  // 2) Instantiate one Queue per rank
  val queues = Seq.fill(numRanks) {
    Module(new Queue(new PhysicalMemoryCommand, entries = depth))
  }

  // 3) Default wiring
  for ((q, i) <- queues.zipWithIndex) {
    // Dequeue side
    io.deq(i)       <> q.io.deq
    io.counts(i)    := q.io.count

    // Prevent accidental enq
    q.io.enq.bits   := io.enq.bits
    q.io.enq.valid  := false.B
  }

  // 4) Only enqueue to the matching rank’s queue
  for ((q, i) <- queues.zipWithIndex) {
    when(rankIdx === i.U) {
      q.io.enq.valid := io.enq.valid
    }
  }

  // 5) Input ready when the selected subqueue can accept
  io.enq.ready := queues
    .zipWithIndex
    .map { case (q, i) => (rankIdx === i.U) && q.io.enq.ready }
    .reduce(_ || _)
}
