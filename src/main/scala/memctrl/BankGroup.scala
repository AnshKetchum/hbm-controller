// File: src/main/scala/memctrl/BankGroup.scala
package memctrl

import chisel3._
import chisel3.util._

/** BankGroup: fans a single PhysicalMemoryCommand to one of N banks,
  * then fans out the PhysicalMemoryResponse from that bank.
  */
class BankGroup(params: MemoryConfigurationParameters, bankParams: DRAMBankParameters) extends Module {
  val io = IO(new PhysicalMemoryIO)

  // 1) Decode the bank index from the incoming address
  val decoder    = Module(new AddressDecoder(params))
  decoder.io.addr := io.memCmd.bits.addr
  val bankIndex = decoder.io.bankIndex

  // 2) Instantiate N banks, all sharing the same IO bundle type
  val banks = Seq.fill(params.numberOfBanks)(Module(new DRAMBank(bankParams)))

  // 3) Hook up the common fields of MemCmd to every bank
  banks.foreach { b =>
    b.io.memCmd.bits := io.memCmd.bits
  }

  // 4) Per‐bank .valid: only the addressed bank gets the valid pulse
  banks.zipWithIndex.foreach { case (b, i) =>
    b.io.memCmd.valid := io.memCmd.valid && (bankIndex === i.U)
  }

  // 5) Collect all banks’ ready signals into a Vec, then index with the UInt
  private val readyVec = VecInit(banks.map(_.io.memCmd.ready))
  io.memCmd.ready     := readyVec(bankIndex)

  // 6) Demux response .ready: only the active bank sees your downstream ready
  banks.zipWithIndex.foreach { case (b, i) =>
    b.io.phyResp.ready := io.phyResp.ready && (bankIndex === i.U)
  }

  // 7) Gather all banks’ response valids and bits into Vecs...
  private val respValidVec = VecInit(banks.map(_.io.phyResp.valid))
  private val respAddrVec  = VecInit(banks.map(_.io.phyResp.bits.addr))
  private val respDataVec  = VecInit(banks.map(_.io.phyResp.bits.data))

  // 8) ...and mux them out with the same bankIndex
  io.phyResp.valid       := respValidVec(bankIndex)
  io.phyResp.bits.addr   := respAddrVec(bankIndex)
  io.phyResp.bits.data   := respDataVec(bankIndex)
}
