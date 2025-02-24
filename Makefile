# Makefile for SDRAM Controller Simulation using Icarus Verilog

# List of Verilog source files
VERILOG_SOURCES = sdram_controller.v sdram_model.v tb_sdram_controller.v

# Output simulation executable name
OUTPUT = sim

# Default target: build and run simulation
all: $(OUTPUT)

$(OUTPUT): $(VERILOG_SOURCES)
	iverilog -o $(OUTPUT) $(VERILOG_SOURCES)
	./$(OUTPUT)

# Clean generated files
clean:
	rm -f $(OUTPUT) waveform.vcd
