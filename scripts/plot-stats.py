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

# Drop rows with missing or improper cycle values in input and output
df_in = df_in.dropna(subset=['Cycle'])
df_out = df_out.dropna(subset=['Cycle'])

# Ensure 'Cycle' values are properly numeric (in case of formatting issues)
df_in['Cycle'] = pd.to_numeric(df_in['Cycle'], errors='coerce')
df_out['Cycle'] = pd.to_numeric(df_out['Cycle'], errors='coerce')

# Drop any rows where 'Cycle' is not a valid number after conversion
df_in = df_in.dropna(subset=['Cycle'])
df_out = df_out.dropna(subset=['Cycle'])

# Merge input and output data on 'RequestId' (assuming 'RequestId' column exists)
merged_df = pd.merge(df_in, df_out, on='RequestID', suffixes=('_in', '_out'))

# Split read/write based on the 'Read' and 'Write' columns
reads_in = merged_df[merged_df['Read_in'] == 1].sort_values('Cycle_in').reset_index(drop=True)
writes_in = merged_df[merged_df['Write_in'] == 1].sort_values('Cycle_in').reset_index(drop=True)
reads_out = merged_df[merged_df['Read_out'] == 1].sort_values('Cycle_out').reset_index(drop=True)
writes_out = merged_df[merged_df['Write_out'] == 1].sort_values('Cycle_out').reset_index(drop=True)

# Latencies
n_reads = min(len(reads_in), len(reads_out))
n_writes = min(len(writes_in), len(writes_out))
latencies_reads = reads_out.loc[:n_reads-1, 'Cycle_out'].to_numpy() - reads_in.loc[:n_reads-1, 'Cycle_in'].to_numpy()
latencies_writes = writes_out.loc[:n_writes-1, 'Cycle_out'].to_numpy() - writes_in.loc[:n_writes-1, 'Cycle_in'].to_numpy()

# Generate plots
plot_latency_pdf(latencies_reads, label="read", filename="dramsim_histo_read_latency.pdf")
plot_latency_pdf(latencies_writes, label="write", filename="dramsim_histo_write_latency.pdf")
