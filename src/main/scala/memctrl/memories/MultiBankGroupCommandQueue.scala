package memctrl

import chisel3._
import chisel3.util._

// Demultiplexes a PhysicalMemoryCommand into per-bank-group subqueues
class MultiBankGroupCmdQueue(
  params: MemoryConfigurationParameters,
  numGroups: Int,
  depth: Int
) extends Module {
  val io = IO(new Bundle {
    val enq    = Flipped(Decoupled(new PhysicalMemoryCommand))
    val deq    = Vec(numGroups, Decoupled(new PhysicalMemoryCommand))
    val counts = Output(Vec(numGroups, UInt(log2Ceil(depth+1).W)))
  })

  // 1) Decode bank-group index
  val addrDec   = Module(new AddressDecoder(params))
  addrDec.io.addr := io.enq.bits.addr
  val bgIdx     = addrDec.io.bankGroupIndex

  // 2) Instantiate one Queue per bank group
  val queues = Seq.fill(numGroups) {
    Module(new Queue(new PhysicalMemoryCommand, entries = depth))
  }

  // 3) Default: hook up dequeues and expose counts
  for ((q, i) <- queues.zipWithIndex) {
    io.deq(i)       <> q.io.deq
    io.counts(i)    := q.io.count

    // prevent unwanted enq
    q.io.enq.bits   := io.enq.bits
    q.io.enq.valid  := false.B
  }

  // 4) Only enqueue into the matching bank-group queue
  for ((q, i) <- queues.zipWithIndex) {
    when(bgIdx === i.U) {
      q.io.enq.valid := io.enq.valid
    }
  }

  // 5) Input ready when the selected subqueue can accept
  io.enq.ready := queues
    .zipWithIndex
    .map { case (q, i) => (bgIdx === i.U) && q.io.enq.ready }
    .reduce(_ || _)
}
