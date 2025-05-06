.PHONY: all clean verilog verilator-run

SRC_DIR      := src
VERILOG_DIR  := $(SRC_DIR)/main/resources/vsrc
SIM_MAIN     := sim_main.cpp
TOP_MODULE   := SingleChannelSystem

# SBT command to run the Chisel elaboration using the legacy driver
SBT_CMD      := sbt "runMain memctrl.Elaborate --target-dir $(VERILOG_DIR)"

# Verilator command and flags
VERILATOR    := verilator
VERILATOR_OPTS := --cc --exe --build

# Gather all Verilog source files from the Verilog directory
VERILOG_FILES := $(wildcard $(VERILOG_DIR)/*.sv)

# Output binary target (Verilator generates C++ simulation in obj_dir/)
TARGET       := obj_dir/V$(TOP_MODULE)

# Rule to generate Verilog from Chisel
verilog:
	@echo "Generating Verilog from Chisel..."
	$(SBT_CMD)
	sed -i '/PerformanceStatisticsInput.sv/d' $(VERILOG_DIR)/SingleChannelSystem.sv
	sed -i '/PerformanceStatisticsOutput.sv/d' $(VERILOG_DIR)/SingleChannelSystem.sv

verilator-random: 
	verilator --cc --exe --build -Mdir obj_dir -o V$(TOP_MODULE) src/main/resources/vsrc/SingleChannelSystem.sv ./sims/sim_random.cpp
	./$(TARGET)
	
verilator-trace: 
	verilator --cc --exe --build -Mdir obj_dir -o V$(TOP_MODULE) src/main/resources/vsrc/SingleChannelSystem.sv ./sims/sim_trace.cpp


all: verilog verilator-trace

# Clean up generated files
clean:
	@echo "Cleaning up simulation files..."
	rm -rf obj_dir
	rm -f V$(TOP_MODULE)
	rm -f *.csv
	rm -f *.log
	rm -f *.png
