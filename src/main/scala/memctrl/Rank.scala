// File: src/main/scala/memctrl/Rank.scala
package memctrl

import chisel3._
import chisel3.util._

/** Rank: fans a single PhysicalMemoryCommand to one of M bank‑groups,
  * then fans out the PhysicalMemoryResponse from that group.
  */
class Rank(params: MemoryConfigurationParameters, bankParams: DRAMBankParameters) extends PhysicalMemoryModuleBase {

  // 1) Decode the bank‑group index from the incoming address
  val decoder       = Module(new AddressDecoder(params))
  decoder.io.addr   := io.memCmd.bits.addr
  val bgIndex       = decoder.io.bankGroupIndex

  // 2) Instantiate one BankGroup per bank‑group
  val groups        = Seq.fill(params.numberOfBankGroups)(
                        Module(new BankGroup(params, bankParams))
                      )

  // 3) Wire the command bits into every group
  groups.foreach { g =>
    g.io.memCmd.bits := io.memCmd.bits
  }

  // 4) Only the addressed group sees valid=true
  groups.zipWithIndex.foreach { case (g, i) =>
    g.io.memCmd.valid := io.memCmd.valid && (bgIndex === i.U)
  }

  // 5) memCmd.ready reflects only the addressed group's ready
  io.memCmd.ready := VecInit(groups.map(_.io.memCmd.ready))(bgIndex)

  // 6) Demux response ready: only the active group sees downstream backpressure
  groups.zipWithIndex.foreach { case (g, i) =>
    g.io.phyResp.ready := io.phyResp.ready && (bgIndex === i.U)
  }

  // 7) Gather all groups’ response signals into Vecs
  private val respValidVec = VecInit(groups.map(_.io.phyResp.valid))
  private val respAddrVec  = VecInit(groups.map(_.io.phyResp.bits.addr))
  private val respDataVec  = VecInit(groups.map(_.io.phyResp.bits.data))

  // 8) Mux out the response from the addressed group
  io.phyResp.valid     := respValidVec(bgIndex)
  io.phyResp.bits.addr := respAddrVec(bgIndex)
  io.phyResp.bits.data := respDataVec(bgIndex)
}
