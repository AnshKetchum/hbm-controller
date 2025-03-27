package memctrl

import chisel3._
import chisel3.util._

/** Random Memory Request Generator */
class RandomMemoryRequestGenerator extends Module {
  val io = IO(new Bundle {
    val out = Decoupled(new ControllerRequest)
  })

  // Linear Feedback Shift Register for pseudo-random number generation
  val lfsr = LFSR(32)
  val validReg = RegInit(false.B)
  val currentAddr = RegInit(0.U(32.W)) // To store the generated address

  // Generate random address
  when (!validReg) {
    currentAddr := lfsr // Generate a random address
  }

  // Generate random write request first, followed by a read to the same address
  val writeRequest = Wire(new ControllerRequest)
  val readRequest = Wire(new ControllerRequest)

  // Write request
  writeRequest.addr := currentAddr
  writeRequest.cmd := "b00".U  // Assume 00 represents a write command
  writeRequest.data := lfsr // Using LFSR value as data for the write

  // Read request (same address as write)
  readRequest.addr := currentAddr
  readRequest.cmd := "b01".U  // Assume 01 represents a read command
  readRequest.data := 0.U // No data for a read

  // Output the requests in sequence
  val requestQueue = RegInit(VecInit(Seq.fill(2)(writeRequest)))
  requestQueue(1) := readRequest // Set second request as read

  io.out.bits := requestQueue.head
  io.out.valid := validReg

  // Simple handshake logic: Generate new requests after ready signal
  when(io.out.ready) {
    requestQueue := requestQueue.tail :+ requestQueue.head
    validReg := true.B // Signal that a request is valid
  }
}

/** Top-level module integrating RandomMemoryRequestGenerator with SingleChannelSystem */
class MemoryTestSystem extends Module {
  val io = IO(new Bundle {
    val out = Decoupled(new ControllerResponse)
  })

  val memSystem = Module(new SingleChannelSystem())
  val reqGen = Module(new RandomMemoryRequestGenerator())

  memSystem.io.in <> reqGen.io.out
  io.out <> memSystem.io.out
}
