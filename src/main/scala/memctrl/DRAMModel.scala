package memctrl

import chisel3._
import chisel3.util._

class DRAMModelIO extends Bundle {
  // Inputs
  val cs = Input(Bool())
  val ras = Input(Bool())
  val cas = Input(Bool())
  val we = Input(Bool())
  val addr = Input(UInt(32.W))
  val wdata = Input(UInt(32.W))
  
  // Outputs
  val response_complete = Output(Bool())
  val response_data = Output(UInt(32.W))
}

class DRAMModel extends Module {
  val io = IO(new DRAMModelIO())
  
  // DRAM states
  object DRAMState extends ChiselEnum {
    val DRAM_IDLE, DRAM_ACTIVATE, DRAM_WAIT, DRAM_PRECHARGE = Value
  }
  
  // Timing parameters (delays in cycles)
  val tRCD_DELAY = 5.U  // Row to column delay
  val tCL_DELAY = 5.U   // CAS latency delay
  val tPRE_DELAY = 10.U // Precharge delay
  val tREFRESH = 10.U   // Refresh delay
  
  // Refresh cycles
  val REFRESH_CYCLES = 200.U
  
  // Memory storage using a Memory element
  val memory = Mem(1024, UInt(32.W))
  
  // State registers
  val state = RegInit(DRAMState.DRAM_IDLE)
  val delay_counter = RegInit(-1.S(32.W))
  val prev_delay_counter = RegInit(-1.S(32.W))
  val refresh_cycle_counter = RegInit(0.U(32.W))
  val memory_activated = RegInit(0.U(1.W))
  
  // Default outputs
  io.response_complete := false.B
  io.response_data := 0.U
  
  // Memory corruption for refresh logic
  val memory_corrupted = RegInit(false.B)
  
  // Save previous value of delay counter for edge detection
  prev_delay_counter := delay_counter
  
  // Tackle Refresh Logic First
  refresh_cycle_counter := refresh_cycle_counter + 1.U
  
  when(refresh_cycle_counter === REFRESH_CYCLES) {
    refresh_cycle_counter := 0.U
    memory_corrupted := true.B  // Mark memory as corrupted, would need to implement memory corruption
    memory_activated := 0.U
  }
  
  // Handle DRAM commands based on control signals
  when(io.cs === 1.U && io.ras === 0.U && io.cas === 0.U) {
    // Launching operation
    delay_counter := -1.S
  }
  .elsewhen(io.cs === 0.U && io.ras === 0.U && io.cas === 0.U && io.we === 1.U) {
    // Refresh operation
    when(delay_counter === -1.S) {
      delay_counter := tREFRESH.asSInt
    }
    
    when(delay_counter > 0.S) {
      delay_counter := delay_counter - 1.S
      io.response_complete := false.B
    }
    .elsewhen(delay_counter === 0.S) {
      refresh_cycle_counter := 0.U
      memory_activated := 0.U
      delay_counter := -1.S
      io.response_complete := true.B
    }
  }
  .elsewhen(io.cs === 0.U && io.ras === 0.U && io.cas === 1.U && io.we === 1.U) {
    // Activate operation
    when(delay_counter === -1.S) {
      delay_counter := tRCD_DELAY.asSInt
    }
    
    when(delay_counter > 0.S) {
      delay_counter := delay_counter - 1.S
      io.response_complete := false.B
    }
    .elsewhen(delay_counter === 0.S) {
      memory_activated := 1.U
      delay_counter := -1.S
      io.response_complete := true.B
    }
  }
  .elsewhen(io.cs === 0.U && io.ras === 1.U && io.cas === 0.U && memory_activated === 1.U) {
    // Read/Write operation
    when(delay_counter === -1.S) {
      delay_counter := tCL_DELAY.asSInt
    }
    
    when(delay_counter > 0.S) {
      delay_counter := delay_counter - 1.S
      io.response_complete := false.B
    }
    .elsewhen(delay_counter === 0.S) {
      when(io.we === 1.U) {
        // Read operation
        io.response_data := memory.read(io.addr)
      }
      .elsewhen(io.we === 0.U) {
        // Write operation
        memory.write(io.addr, io.wdata)
        io.response_data := io.wdata
        printf("[DRAM] Writing %d to address 0x%x\n", io.wdata, io.addr)
      }
      io.response_complete := true.B
      delay_counter := -1.S
    }
  }
  .elsewhen(io.cs === 0.U && io.ras === 0.U && io.cas === 1.U && io.we === 0.U) {
    // Precharge operation
    when(delay_counter === -1.S) {
      delay_counter := tPRE_DELAY.asSInt
    }
    
    when(delay_counter > 0.S) {
      delay_counter := delay_counter - 1.S
      io.response_complete := false.B
    }
    .elsewhen(delay_counter === 0.S) {
      delay_counter := -1.S
      memory_activated := 0.U
      io.response_complete := true.B
    }
  }
  
  // Debug: Print current state
  when(delay_counter =/= prev_delay_counter) {
    printf("[DRAM] Delay counter: %d\n", delay_counter)
  }
}

object DRAMModelMain extends App {
  chisel3.emitVerilog(new DRAMModel(), Array("--target-dir", "generated"))
}