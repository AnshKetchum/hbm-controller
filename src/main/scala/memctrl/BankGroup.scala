package memctrl

import chisel3._
import chisel3.util._

/** BankGroup: safely routes commands to banks and muxes responses back */
class BankGroup(params: MemoryConfigurationParameters, bankParams: DRAMBankParameters)
    extends PhysicalMemoryModuleBase {

  // === 1. Address decode ===
  val decoder = Module(new AddressDecoder(params))
  decoder.io.addr := io.memCmd.bits.addr
  val decodedBankIndex = decoder.io.bankIndex

  // === 2. Instantiate banks ===
  val banks = Seq.fill(params.numberOfBanks)(Module(new DRAMBank(bankParams)))

  // === 3. Request Queue for each bank ===
  val reqQueues = Seq.fill(params.numberOfBanks)(Module(new Queue(new PhysicalMemoryCommand, 4)))

  // === 4. Demux command to the appropriate request queue ===
  for (i <- 0 until params.numberOfBanks) {
    reqQueues(i).io.enq.valid := io.memCmd.valid && (decodedBankIndex === i.U)
    reqQueues(i).io.enq.bits  := io.memCmd.bits

    when(reqQueues(i).io.enq.fire) {
      printf(
        p"[BankGroup] Request enqueued to bank $i: addr=0x${Hexadecimal(io.memCmd.bits.addr)} data=0x${Hexadecimal(io.memCmd.bits.data)}\n"
      )
    }
  }

  // === 5. Ready signal for the memCmd based on bank selection ===
  io.memCmd.ready := reqQueues
    .map(_.io.enq.ready)
    .zipWithIndex
    .map { case (rdy, i) =>
      Mux(decodedBankIndex === i.U, rdy, false.B)
    }
    .reduce(_ || _)

  // === 6. Connect queues to banks ===
  for (i <- 0 until params.numberOfBanks) {
    banks(i).io.memCmd <> reqQueues(i).io.deq
  }

  // === 7. Response Queue for each bank ===
  val respQueues = Seq.fill(params.numberOfBanks)(Module(new Queue(new PhysicalMemoryResponse, 4)))

  // === 8. Connect response queues to bank responses ===
  for (i <- 0 until params.numberOfBanks) {
    respQueues(i).io.enq <> banks(i).io.phyResp

    when(respQueues(i).io.enq.fire) {
      printf(p"[BankGroup] Response enqueued from bank $i: addr=0x${Hexadecimal(
          banks(i).io.phyResp.bits.addr
        )} data=0x${Hexadecimal(banks(i).io.phyResp.bits.data)}\n")
    }
  }

  // === 9. Arbiter to select one response to send out ===
  val arbResp = Module(new RRArbiter(new PhysicalMemoryResponse, params.numberOfBanks))
  for (i <- 0 until params.numberOfBanks) {
    arbResp.io.in(i) <> respQueues(i).io.deq
  }

  io.phyResp <> arbResp.io.out

  // === 10. Active sub-memories ===
  val activeSubMemoriesVec = VecInit(banks.map(_.io.activeSubMemories))
  io.activeSubMemories := activeSubMemoriesVec.reduce(_ +& _)
}
