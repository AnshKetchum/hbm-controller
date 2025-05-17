# Makefile to run ablations
PYTHON := python
SIMULATION_CYCLES := 100000
SCALE := 1000

# Experiment Directories
QUEUE_ABLATIONS_EXPERIMENT_DIR := queue_ablation_experiments


# Run tests on the queue size and see how it impacts overall performance
run-queue-size-ablations-sanity:
	$(PYTHON) scripts/evaluate/ablate_on_queues.py --sim ./obj_dir/VSingleChannelSystem --trace traces/conv2d_trace.txt --outdir $(QUEUE_ABLATIONS_EXPERIMENT_DIR) --csv_dir . --cycles $(SIMULATION_CYCLES) --start 256 --end 256

# Warning: This run will take at least 5 mins, and at most 45 mins.
run-queue-size-ablations:
	$(PYTHON) scripts/evaluate/ablate_on_queues.py --sim ./obj_dir/VSingleChannelSystem --trace traces/conv2d_trace.txt --outdir $(QUEUE_ABLATIONS_EXPERIMENT_DIR) --csv_dir . --cycles $(SIMULATION_CYCLES) --start 1 --end 512

run-cycle-latencies-profile:
	$(PYTHON) scripts/evaluate/evaluate_cycle_latencies_current.py exps_128_q/current/exp_conv2d_trace/meta/ --scale $(SCALE)

run-ablation-sanity-checks: run-queue-size-ablations-sanity run-cycle-latencies-profile