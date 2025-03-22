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
  
  // DRAM states (not used extensively in this example)
  object DRAMState extends ChiselEnum {
    val DRAM_IDLE, DRAM_ACTIVATE, DRAM_WAIT, DRAM_PRECHARGE = Value
  }
  
  // Timing parameters (delays in cycles)
  val tRCD_DELAY = 5.U  // Row to column delay
  val tCL_DELAY  = 5.U  // CAS latency delay
  val tPRE_DELAY = 10.U // Precharge delay
  val tREFRESH   = 10.U // Refresh delay
  
  // Refresh cycles (for simulation of periodic refresh)
  val REFRESH_CYCLES = 200.U
  
  // Memory storage using a Memory element
  val memory = Mem(1024, UInt(32.W))
  
  // Registers
  val delay_counter        = RegInit(0.U(32.W))
  val refresh_cycle_counter = RegInit(0.U(32.W))
  val memory_activated     = RegInit(0.U(1.W))
  
  // Default outputs
  io.response_complete := false.B
  io.response_data     := 0.U
  
  // Memory corruption flag (for refresh logic)
  val memory_corrupted = RegInit(false.B)
  
  // Tackle Refresh Cycle counter (always counting up)
  refresh_cycle_counter := refresh_cycle_counter + 1.U
  when(refresh_cycle_counter === REFRESH_CYCLES) {
    refresh_cycle_counter := 0.U
    memory_corrupted := true.B  // Mark memory as corrupted (simulate refresh effect)
    memory_activated := 0.U
  }
  
  // For debugging: print current values
  // printf("Core Signal Values: %d %d %d %d\n", io.cs, io.ras, io.cas, io.we)
  // printf("All Signal Values: 0x%x %d\n", io.addr, io.wdata)
  // printf(p"*** [DRAM] Delay Counter: ${delay_counter}. *** \n")
  
  // Compute the next delay value
  val next_delay = WireDefault(delay_counter)
  // printf(p"*** [DRAM] Next Delay Counter: ${next_delay}. *** \n")
  
  // DRAM command processing: use a mutually exclusive branch for each command.
  // In each branch, if the delay counter is zero then load the appropriate delay constant,
  // otherwise decrement the counter. When the counter is about to expire (e.g. equals 1)
  // we complete the command.
  when(io.cs === 1.U) {
    // Command Inhibit: simply reset the delay counter.
    next_delay := 0.U
    io.response_complete := false.B
  } .elsewhen(io.cs === 0.U && io.ras === 0.U && io.cas === 0.U && io.we === 1.U) {
    // Refresh operation
    when(delay_counter === 0.U) {
      next_delay := tREFRESH
      io.response_complete := false.B
      printf("Refreshing: Loaded tREFRESH (%d cycles)\n", tREFRESH)
    } .otherwise {
      next_delay := delay_counter - 1.U
      when(delay_counter === 1.U) {
        // When the delay expires, finish the refresh.
        refresh_cycle_counter := 0.U
        memory_activated := 0.U
        io.response_complete := true.B
      } .otherwise {
        io.response_complete := false.B
      }
    }
  } .elsewhen(io.cs === 0.U && io.ras === 0.U && io.cas === 1.U && io.we === 1.U) {
    // Activate operation
    when(delay_counter === 0.U) {
      next_delay := tRCD_DELAY
      io.response_complete := false.B
      printf("Activating: Loaded tRCD_DELAY (%d cycles)\n", tRCD_DELAY)
    } .otherwise {
      next_delay := delay_counter - 1.U
      when(delay_counter === 1.U) {
        memory_activated := 1.U
        io.response_complete := true.B
        printf("Completed activation.")
      } .otherwise {
        printf("Not completed activation.")
        io.response_complete := false.B
      }
    }
  } .elsewhen(io.cs === 0.U && io.ras === 1.U && io.cas === 0.U && memory_activated === 1.U) {
    // Read/Write operation
    when(delay_counter === 0.U) {
      next_delay := tCL_DELAY
      io.response_complete := false.B
      printf("Read/Write: Loaded tCL_DELAY (%d cycles)\n", tCL_DELAY)
    } .otherwise {
      next_delay := delay_counter - 1.U
      when(delay_counter === 1.U) {
        // On completion, perform the read or write.
        when(io.we === 1.U) {
          // Read operation
          io.response_data := memory.read(io.addr)
          printf("Reading data %d from address 0x%x\n", memory.read(io.addr), io.addr)
        } .otherwise {
          // Write operation
          memory.write(io.addr, io.wdata)
          io.response_data := io.wdata
          printf("[DRAM] Writing %d to address 0x%x\n", io.wdata, io.addr)
        }
        io.response_complete := true.B
      } .otherwise {
        io.response_complete := false.B
      }
    }
  } .elsewhen(io.cs === 0.U && io.ras === 0.U && io.cas === 1.U && io.we === 0.U) {
    // Precharge operation
    when(delay_counter === 0.U) {
      next_delay := tPRE_DELAY
      io.response_complete := false.B
      printf("Precharge: Loaded tPRE_DELAY (%d cycles)\n", tPRE_DELAY)
    } .otherwise {
      next_delay := delay_counter - 1.U
      when(delay_counter === 1.U) {
        memory_activated := 0.U
        io.response_complete := true.B
      } .otherwise {
        io.response_complete := false.B
      }
    }
  } .otherwise {
    next_delay := delay_counter
    io.response_complete := false.B
  }
  
  // Update the delay counter at the clock edge.
  delay_counter := next_delay
}

object DRAMModelMain extends App {
  chisel3.emitVerilog(new DRAMModel(), Array("--target-dir", "generated"))
}
