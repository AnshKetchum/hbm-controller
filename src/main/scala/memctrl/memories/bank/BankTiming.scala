package memctrl

import chisel3._
import chisel3.util._

object DRAMOp {
  val ACTIVATE   = 0.U(3.W)
  val READ       = 1.U(3.W)
  val WRITE      = 2.U(3.W)
  val READ_PRECHARGE  = 3.U(3.W)
  val WRITE_PRECHARGE = 4.U(3.W)
  val PRECHARGE  = 5.U(3.W)
  val REFRESH    = 6.U(3.W)
  val SREF_ENTER = 7.U(3.W)
  val N_OPS      = 8
  // Scala ints for matching
  val ACTIVATE_INT   = 0
  val READ_INT       = 1
  val WRITE_INT      = 2
  val RP_INT         = 3
  val WP_INT         = 4
  val PRE_INT        = 5
  val REF_INT        = 6
  val SREF_INT       = 7
}

class TimingEngine(params: DRAMBankParameters) extends Module {
  val io = IO(new Bundle {
    val cmd        = Flipped(Decoupled(new BankMemoryCommand))
    val waitCycles = Output(UInt(32.W))
  })

  // Prev/Cur ops
  val prevOp = RegInit(DRAMOp.N_OPS.U)
    // Decode incoming command into DRAMOp
    val cs_p  = Wire(Bool()); cs_p  := !io.cmd.bits.cs
    val ras_p = Wire(Bool()); ras_p := !io.cmd.bits.ras
    val cas_p = Wire(Bool()); cas_p := !io.cmd.bits.cas
    val we_p  = Wire(Bool()); we_p  := !io.cmd.bits.we

    val currOp = Wire(UInt(3.W))
    currOp := DRAMOp.N_OPS.U  // default invalid op

    switch(Cat(cs_p, ras_p, cas_p, we_p)) {
        is("b1100".U) { currOp := DRAMOp.ACTIVATE   } // cs=1 ras=1 cas=0 we=0
        is("b1010".U) { currOp := DRAMOp.READ       } // cs=1 ras=0 cas=1 we=0
        is("b1011".U) { currOp := DRAMOp.WRITE      } // cs=1 ras=0 cas=1 we=1
        is("b1101".U) { currOp := DRAMOp.PRECHARGE  } // cs=1 ras=1 cas=0 we=1
        is("b1110".U) { currOp := DRAMOp.REFRESH    } // cs=1 ras=1 cas=1 we=0
        is("b1111".U) { currOp := DRAMOp.SREF_ENTER } // cs=1 ras=1 cas=1 we=1
    }

    // When a new command fires, shift curr->prev and decode new opcode
    when(io.cmd.fire) {
        printf("Received command - cs = %d ras = %d cas = %d we = %d\n", io.cmd.bits.cs, io.cmd.bits.ras, io.cmd.bits.cas, io.cmd.bits.we)
        printf("Prev = %d, Cur = %d Wait = %d\n", prevOp, currOp, io.waitCycles)
        prevOp := currOp
    }

  // ----------------------------------------------------------------
  // 1) All same‑bank delays as functions of base params
  // ----------------------------------------------------------------
  val burst       = params.burst_cycle.U(32.W)
  val tCCD_L      = params.tCCD_L.U(32.W)
  val tWTR_L      = params.tWTR_L.U(32.W)
  val tRTRS       = params.tRTRS.U(32.W)
  val RL           = params.RL.U(32.W)
  val WL           = params.WL.U(32.W)
  val AL           = params.AL.U(32.W)
  val tRTP        = params.tRTP.U(32.W)
  val tWR         = params.tWR.U(32.W)
  val tRP         = params.tRP.U(32.W)
  val tRAS        = params.tRAS.U(32.W)
  val tRFC        = params.tRFC.U(32.W)
  val tRRD_L      = params.tRRD_L.U(32.W)
  val tRCDRD      = params.tRCDRD.U(32.W)
  val tRCDWR      = params.tRCDWR.U(32.W)
  val CWL          = params.CWL.U(32.W)
  val tRTP_S      = params.tRTP_S.U(32.W)
  val tXS          = params.tXS.U(32.W)

  // Derived delays:
  val read_to_read_l      = Mux(burst > tCCD_L, burst, tCCD_L)
  val read_to_write       = RL + burst - WL + tRTRS
  val read_to_precharge   = AL + tRTP
  val readp_to_activate   = AL + burst + tRTP + tRP

  val write_to_read_l     = WL + burst + tRTRS - RL
  val write_to_write_l    = Mux(burst > tCCD_L, burst, tCCD_L)
  val write_to_precharge  = WL + burst + tWR
  val writep_to_activate  = write_to_precharge + tRP

  val precharge_to_activate = tRP

  val activate_to_act_l   = tRRD_L
  val activate_to_read    = tRCDRD
  val activate_to_write   = tRCDWR
  val activate_to_precharge = tRAS

  val refresh_to_activate = tRFC

  // ----------------------------------------------------------------
  // 2) same‑bank timing matrix
  // ----------------------------------------------------------------
  val timing = VecInit(Seq.tabulate(DRAMOp.N_OPS) { i =>
    VecInit(Seq.tabulate(DRAMOp.N_OPS) { j =>
      (i, j) match {
        case (DRAMOp.READ_INT,  DRAMOp.READ_INT)  => read_to_read_l
        case (DRAMOp.READ_INT,  DRAMOp.WRITE_INT) => read_to_write
        case (DRAMOp.READ_INT,  DRAMOp.PRE_INT)   => read_to_precharge
        case (DRAMOp.READ_INT,  DRAMOp.RP_INT)    => read_to_read_l
        case (DRAMOp.READ_INT,  DRAMOp.WP_INT)    => read_to_write

        case (DRAMOp.WRITE_INT, DRAMOp.READ_INT)  => write_to_read_l
        case (DRAMOp.WRITE_INT, DRAMOp.WRITE_INT) => write_to_write_l
        case (DRAMOp.WRITE_INT, DRAMOp.PRE_INT)   => write_to_precharge
        case (DRAMOp.WRITE_INT, DRAMOp.RP_INT)    => write_to_read_l
        case (DRAMOp.WRITE_INT, DRAMOp.WP_INT)    => write_to_write_l

        case (DRAMOp.RP_INT,    DRAMOp.ACTIVATE_INT) => readp_to_activate
        case (DRAMOp.RP_INT,    DRAMOp.REF_INT)      => readp_to_activate
        case (DRAMOp.RP_INT,    DRAMOp.SREF_INT)     => readp_to_activate

        case (DRAMOp.WP_INT,    DRAMOp.ACTIVATE_INT) => writep_to_activate
        case (DRAMOp.WP_INT,    DRAMOp.REF_INT)      => writep_to_activate
        case (DRAMOp.WP_INT,    DRAMOp.SREF_INT)     => writep_to_activate

        case (DRAMOp.ACTIVATE_INT, DRAMOp.ACTIVATE_INT) => activate_to_act_l
        case (DRAMOp.ACTIVATE_INT, DRAMOp.READ_INT)     => activate_to_read
        case (DRAMOp.ACTIVATE_INT, DRAMOp.WRITE_INT)    => activate_to_write
        case (DRAMOp.ACTIVATE_INT, DRAMOp.PRE_INT)      => activate_to_precharge

        case (DRAMOp.PRE_INT,    DRAMOp.ACTIVATE_INT)   => precharge_to_activate
        case (DRAMOp.PRE_INT,    DRAMOp.REF_INT)        => precharge_to_activate
        case (DRAMOp.PRE_INT,    DRAMOp.SREF_INT)       => precharge_to_activate

        case (DRAMOp.REF_INT,    DRAMOp.ACTIVATE_INT)   => refresh_to_activate
        case (DRAMOp.REF_INT,    DRAMOp.SREF_INT)       => refresh_to_activate

        case (DRAMOp.SREF_INT,   DRAMOp.SREF_INT)       => tXS

        case _                                          => 1.U(32.W)
      }
    })
  })

  // ----------------------------------------------------------------
  // 3) Final lookup
  // ----------------------------------------------------------------
    when(prevOp === DRAMOp.N_OPS.U || currOp === DRAMOp.N_OPS.U) {
        io.waitCycles := 0.U
    } .otherwise {
        io.waitCycles := timing(prevOp)(currOp)
    }

  io.cmd.ready := true.B
}
