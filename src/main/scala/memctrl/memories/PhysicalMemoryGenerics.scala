// File: src/main/scala/memctrl/BankModel.scala
package memctrl

import chisel3._
import chisel3.util._

/** Memory Command interface (to external memory) **/
class PhysicalMemoryCommand extends Bundle {
  val addr = UInt(32.W)
  val data = UInt(32.W)
  val cs   = Bool()
  val ras  = Bool()
  val cas  = Bool()
  val we   = Bool()
  val request_id = UInt(32.W)
}

/** Physical Memory Response interface **/
class PhysicalMemoryResponse extends Bundle {
  val addr = UInt(32.W)
  val data = UInt(32.W)
  val request_id = UInt(32.W)
}


/** Generic Physical Memory I/O: decoupled command in, decoupled response out **/
class PhysicalMemoryIO extends Bundle {
  /** Input command from controller **/
  val memCmd  = Flipped(Decoupled(new PhysicalMemoryCommand))
  /** Output response back to controller **/
  val phyResp = Decoupled(new PhysicalMemoryResponse)
  /** Output active sub-memories count **/
  val activeSubMemories = Output(UInt(32.W)) // Track number of active sub-memories
}

/** Memory Command interface (to external memory) **/
class BankMemoryCommand extends Bundle {
  val addr = UInt(32.W)
  val data = UInt(32.W)
  val cs   = Bool()
  val ras  = Bool()
  val cas  = Bool()
  val we   = Bool()
  val request_id = UInt(32.W)
  val lastColBankGroup = UInt(32.W)
  val lastColCycle = UInt(32.W)
}

/** Physical Memory Response interface **/
class BankMemoryResponse extends Bundle {
  val addr = UInt(32.W)
  val data = UInt(32.W)
  val request_id = UInt(32.W)
}

/** Physical Memory I/O for DRAMBank: decoupled command in, decoupled response out **/
class PhysicalBankIO extends Bundle {
  /** Input command from controller **/
  val memCmd  = Flipped(Decoupled(new BankMemoryCommand))
  /** Output response back to controller **/
  val phyResp = Decoupled(new BankMemoryResponse)
  /** Output active sub-memories count **/
  val activeSubMemories = Output(UInt(32.W)) // Track number of active sub-memories
}

/** HBM2 timing parameters + ACK constant **/
case class DRAMBankParameters(
  numRows:    Int = 32768,
  numCols:    Int = 64,
  deviceWidth:Int = 128,
  tCK:        Int = 1,
  CL:         Int = 14,
  CWL:        Int = 4,
  tRCDRD:     Int = 14,
  tRCDWR:     Int = 14,
  tRP:        Int = 14,
  tRAS:       Int = 34,
  tRFC:       Int = 3,
  tREFI:      Int = 3900,
  tREFIb:     Int = 128,
  tRPRE:      Int = 1,
  tWPRE:      Int = 1,
  tRRD_S:     Int = 4,
  tRRD_L:     Int = 6,
  tWTR_S:     Int = 6,
  tWTR_L:     Int = 8,
  tFAW:       Int = 30,
  tWR:        Int = 16,
  tCCD_S:     Int = 1,
  tCCD_L:     Int = 2,
  tXS:        Int = 268,
  tCKE:       Int = 8,
  tCKSRE:     Int = 10,
  tXP:        Int = 8,
  tRTP_L:     Int = 6,
  tRTP_S:     Int = 4,
  /** Constant to return as ‘ACK’ on non‑data operations **/
  ack:        Int = 0

) {
  require(numRows > 0 && numCols > 0, "numRows and numCols must be positive")
  val addressSpaceSize = numRows * numCols
  val ackData: UInt     = ack.U(32.W)
}

case class MemoryConfigurationParameters(
  numberOfRanks:      Int = 2,
  numberOfBankGroups: Int = 2,
  numberOfBanks:      Int = 8
)

case class LocalConfigurationParameters(
  channelIndex:      Int,
  rankIndex:      Int,
  bankGroupIndex: Int,
  bankIndex: Int
)

/**
 * Base class for any non-bank module exposing a PhysicalMemoryIO interface
 */
abstract class PhysicalMemoryModuleBase extends Module {
  val io = IO(new PhysicalMemoryIO)
}

/**
 * Base class for the bank module 
 */
abstract class PhysicalBankModuleBase extends Module {
  val io = IO(new PhysicalBankIO)
}