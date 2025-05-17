# Evaluation Parameters
PYTHON := python
EXAMPLES_DIR := examples
TRACES_DIR := traces

EXPERIMENT_DIR := exps/current
DRAMSIM_EXPERIMENT_DIR := exps/dramsim
DIFF_EXPERIMENTS_DIR := exps/diff

TOTAL_SIMULATION_CYCLES := 100000

# DRAMSim3 Configuration
DRAMSIM_BINARY := /home/nixos/CodingWorkspace/hardware/mem-controller/DRAMsim3/build/dramsim3main
DRAMSIM_MEMORY_CONFIG := HBM2_4Gb_x128.ini

.PHONY: convert-traces evaluate-current evaluate-dramsim3 compare-experiments post-job-cleanup evaluate evaluate-dramsim evaluate-all

# Convert C programs to trace format
convert-traces:
	rm -rf $(TRACES_DIR)
	$(PYTHON) scripts/preprocess/convert_c_to_traces.py $(EXAMPLES_DIR) -o $(TRACES_DIR)

evaluate-current-no-rebuild: 
	rm -rf $(EXPERIMENT_DIR)
	$(PYTHON) scripts/evaluate/evaluate_trace_current.py --sim $(TARGET) --traces $(TRACES_DIR) --outdir $(EXPERIMENT_DIR) --csv_dir . --cycles $(TOTAL_SIMULATION_CYCLES)

# Evaluate custom Chisel-based simulator
# verilator-trace and verilog should be a job in build_simulator that elaborates our Chisel + builds our sim exe
evaluate-current: convert-traces verilog verilator-trace evaluate-current-no-rebuild

visualize-current:
	$(PYTHON) scripts/visualize/visualize_experiments.py $(EXPERIMENT_DIR) --num-cycles $(TOTAL_SIMULATION_CYCLES) --prefix current

# Evaluate DRAMSim3 reference
evaluate-dramsim3: convert-traces
	rm -rf $(DRAMSIM_EXPERIMENT_DIR)
	$(PYTHON) scripts/evaluate/evaluate_trace_dramsim3.py --sim $(DRAMSIM_BINARY) --traces $(TRACES_DIR) --outdir $(DRAMSIM_EXPERIMENT_DIR) --csv_dir . --cycles $(TOTAL_SIMULATION_CYCLES) --dramsim-config $(DRAMSIM_MEMORY_CONFIG)

visualize-dramsim3:
	$(PYTHON) scripts/visualize/visualize_experiments.py $(DRAMSIM_EXPERIMENT_DIR) --num-cycles $(TOTAL_SIMULATION_CYCLES) --prefix dramsim

# Compare Chisel vs DRAMSim3 results
compare-experiments:
	$(PYTHON) scripts/compare/diff_experiments.py --current-dir $(EXPERIMENT_DIR) --baseline-dir $(DRAMSIM_EXPERIMENT_DIR) --out-dir $(DIFF_EXPERIMENTS_DIR)

# Post-run cleanup of metadata and intermediate outputs
post-job-cleanup: clean
	mkdir -p $(DRAMSIM_EXPERIMENT_DIR)/metadata
	mv dramsim3.json $(DRAMSIM_EXPERIMENT_DIR)/metadata || true
	mv dramsim3.txt $(DRAMSIM_EXPERIMENT_DIR)/metadata || true
	mv *cmd.trace $(DRAMSIM_EXPERIMENT_DIR)/metadata || true
	rm -f input_request_stats.csv output_request_stats.csv

# Full pipeline evaluations
evaluate: evaluate-current
evaluate-dramsim: evaluate-dramsim3 visualize-dramsim3

evaluate-all: clean evaluate-current visualize-current evaluate-dramsim3 visualize-dramsim3 compare-experiments post-job-cleanup

# Clean up all outputs
clean:
	rm -f src/main/resources/vsrc/SingleChannelSystem.sv
	rm -rf $(EXPERIMENT_DIR) $(DRAMSIM_EXPERIMENT_DIR) $(DIFF_EXPERIMENTS_DIR) $(TRACES_DIR) 
	rm -f *.trace 
	rm -f *.csv
	rm -f *.json
