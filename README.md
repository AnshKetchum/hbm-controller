# An RTL Level Simulation Model for High Bandwidth Memory 

This project is under active development. 

Current goals include:
- Implementing an RTL level model of HBM memory for simulations
- Obtaining high fidelity timing data based on sending in address traces

Current Codebase Guarentees from Verification
- If ANY DRAM Bank fails to refresh by the parameter deadline, the simulation stops
- All Physical Memory Modules have *the same I/O interface*, and can be driven by the mem controller FSM.


Default HBM model Parameters are currently set to - https://github.com/umd-memsys/DRAMsim3/blob/master/configs/HBM2_4Gb_x128.ini


Known Limitations
- DRAM Mem Conflicts; impacts correctness of simulation, not timing 