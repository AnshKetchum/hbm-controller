package memctrl

import chisel3._
import chisel3.util._


class ChannelIO extends Bundle {
  // Input from the memory controller: now a single Decoupled MemCmd interface.
  val memCmd = Flipped(Decoupled(new MemCmd))

  // Output: Decoupled physical memory response.
  val phyResp = Decoupled(new PhysicalMemResponse)
}

/** Channel Module
  * Now instantiates an AddressDecoder to obtain the rank index.
  */
class Channel(params: MemoryConfigurationParams = MemoryConfigurationParams(), bankParams: DRAMBankParams = DRAMBankParams()) extends Module {
  val io = IO(new Bundle {
    // Define your Channel I/O (example below)
    val memCmd = Flipped(Decoupled(new Bundle {
      val cs   = Bool()
      val ras  = Bool()
      val cas  = Bool()
      val we   = Bool()
      val addr = UInt(32.W)
      val data = UInt(32.W)
    }))
    val phyResp = Decoupled(new Bundle {
      val addr = UInt(32.W)
      val data = UInt(32.W)
    })
  })

  // Instantiate the address decoder
  val addrDecoder = Module(new AddressDecoder(params))
  addrDecoder.io.addr := io.memCmd.bits.addr

  // Get rank index from the decoder
  val rankIndex = addrDecoder.io.rankIndex

  // Instantiate rank modules
  val ranks = Seq.fill(params.numberOfRanks)(Module(new Rank(params, bankParams)))
  for ((rank, i) <- ranks.zipWithIndex) {
    val isActiveRank = (rankIndex === i.U)
    rank.io.cs    := Mux(isActiveRank, io.memCmd.bits.cs, false.B)
    rank.io.ras   := Mux(isActiveRank, io.memCmd.bits.ras, false.B)
    rank.io.cas   := Mux(isActiveRank, io.memCmd.bits.cas, false.B)
    rank.io.we    := Mux(isActiveRank, io.memCmd.bits.we, false.B)
    rank.io.addr  := io.memCmd.bits.addr
    rank.io.wdata := io.memCmd.bits.data
  }

  // Collect responses from all ranks and forward the selected one.
  val responseCompleteVec = VecInit(ranks.map(_.io.response_complete))
  val responseDataVec     = VecInit(ranks.map(_.io.response_data))

  io.phyResp.valid     := responseCompleteVec(rankIndex)
  io.phyResp.bits.addr := io.memCmd.bits.addr
  io.phyResp.bits.data := responseDataVec(rankIndex)

  // Decoupled interface ready signal
  io.memCmd.ready := true.B
}