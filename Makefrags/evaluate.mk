
# Evaluation Parameters
PYTHON := python
EXAMPLES_DIR := examples
TRACES_DIR := traces
EXPERIMENT_DIR := exp
TOTAL_SIMULATION_CYCLES := 100000

## Evaluation flows
evaluate: 
	rm -rf $(EXPERIMENT_DIR) $(TRACES_DIR)
	$(PYTHON) scripts/preprocess/convert_c_to_traces.py $(EXAMPLES_DIR) -o $(TRACES_DIR)
	$(PYTHON) scripts/evaluate/evaluate_traces.py --sim $(TARGET) --traces $(TRACES_DIR) --outdir $(EXPERIMENT_DIR) --csv_dir . --cycles $(TOTAL_SIMULATION_CYCLES)
	$(PYTHON) scripts/visualize/visualize_experiments.py $(EXPERIMENT_DIR)