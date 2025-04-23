#!/usr/bin/env python3
import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

def plot_latency_pdf(latencies, label, filename):
    average_latency = np.mean(latencies)
    p99_latency = np.percentile(latencies, 99)
    max_latency = np.max(latencies)

    plt.figure(figsize=(8, 6))
    plt.hist(latencies, bins=100, density=True, color='steelblue', edgecolor='black', alpha=0.75)

    plt.axvline(average_latency, color='green', linestyle='--', linewidth=2, label=f'Average: {average_latency:.1f}')
    plt.axvline(p99_latency, color='red', linestyle='dashdot', linewidth=2, label=f'99 Percentile: {p99_latency:.1f}')

    plt.title(f"{label}_latency")
    plt.xlabel(f"{label}_latency [max: {max_latency}](cycles)")
    plt.ylabel("Density")
    plt.legend()
    plt.tight_layout()
    plt.savefig(filename)
    plt.close()

# Input files
input_csv = "input_request_stats.csv"
output_csv = "output_request_stats.csv"

# Load data
df_in = pd.read_csv(input_csv, skipinitialspace=True)
df_out = pd.read_csv(output_csv, skipinitialspace=True)

# Split read/write
reads_in = df_in[df_in['Read'] == 1].sort_values('Cycle').reset_index(drop=True)
writes_in = df_in[df_in['Write'] == 1].sort_values('Cycle').reset_index(drop=True)
reads_out = df_out[df_out['Read'] == 1].sort_values('Cycle').reset_index(drop=True)
writes_out = df_out[df_out['Write'] == 1].sort_values('Cycle').reset_index(drop=True)

# Latencies
n_reads = min(len(reads_in), len(reads_out))
n_writes = min(len(writes_in), len(writes_out))
latencies_reads = reads_out.loc[:n_reads-1, 'Cycle'].to_numpy() - reads_in.loc[:n_reads-1, 'Cycle'].to_numpy()
latencies_writes = writes_out.loc[:n_writes-1, 'Cycle'].to_numpy() - writes_in.loc[:n_writes-1, 'Cycle'].to_numpy()

# Generate plots
plot_latency_pdf(latencies_reads, label="read", filename="dramsim_histo_read_latency.pdf")
plot_latency_pdf(latencies_writes, label="write", filename="dramsim_histo_write_latency.pdf")
