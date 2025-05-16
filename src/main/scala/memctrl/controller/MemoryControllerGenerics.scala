package memctrl

import chisel3._
import chisel3.util._

//----------------------------------------------------------------------
// Top-level interface bundles (renamed)
//----------------------------------------------------------------------

/** Controller Request interface * */
class ControllerRequest extends Bundle {
  val rd_en = Bool()
  val wr_en = Bool()
  val addr  = UInt(32.W)
  val wdata = UInt(32.W)
  val request_id = UInt(32.W)
}

/** Controller Response interface * */
class ControllerResponse extends Bundle {
  val rd_en = Bool()
  val wr_en = Bool()
  val addr  = UInt(32.W)
  val wdata = UInt(32.W)
  val data  = UInt(32.W) // Keep data since responses might need to return data
  val request_id = UInt(32.W)
}

/** System Request interface **/
class SystemRequest extends Bundle {
  val rd_en = Bool()
  val wr_en = Bool()
  val addr  = UInt(32.W)
  val wdata = UInt(32.W)
}
