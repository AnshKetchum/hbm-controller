# Makefile for SDRAM Controller Simulation using Icarus Verilog

# List of Verilog source files
VERILOG_SOURCES = memory_controller.sv 
CPP_SOURCES = dram_tb.cpp dram_model.cpp

# Default target: build and run simulation
all: build

build:
	verilator --cc --exe --build --trace $(VERILOG_SOURCES) $(CPP_SOURCES) --top-module memory_controller

run: build
	./obj_dir/Vmemory_controller

# Clean generated files
clean:
	rm -rf obj_dir waveform.vcd
