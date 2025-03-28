#!/usr/bin/env python3
import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

# Filenames (assumed to remain constant)
input_csv = "input_request_stats.csv"
output_csv = "output_request_stats.csv"

# Read the CSV files (skip any extra whitespace)
df_in = pd.read_csv(input_csv, skipinitialspace=True)
df_out = pd.read_csv(output_csv, skipinitialspace=True)

# Separate read and write requests in both input and output DataFrames.
# Here, we assume each row is either a read or a write (only one of the two flags equals 1).
reads_in = df_in[df_in['Read'] == 1].sort_values('Cycle').reset_index(drop=True)
writes_in = df_in[df_in['Write'] == 1].sort_values('Cycle').reset_index(drop=True)

reads_out = df_out[df_out['Read'] == 1].sort_values('Cycle').reset_index(drop=True)
writes_out = df_out[df_out['Write'] == 1].sort_values('Cycle').reset_index(drop=True)

# Compute the number of matched requests (if some requests have not completed, take the minimum count)
n_reads = min(len(reads_in), len(reads_out))
n_writes = min(len(writes_in), len(writes_out))

# Calculate latencies (difference between output cycle and input cycle)
latencies_reads = reads_out.loc[:n_reads-1, 'Cycle'].to_numpy() - reads_in.loc[:n_reads-1, 'Cycle'].to_numpy()
latencies_writes = writes_out.loc[:n_writes-1, 'Cycle'].to_numpy() - writes_in.loc[:n_writes-1, 'Cycle'].to_numpy()

# Print computed latencies (optional)
print("Read latencies (cycles):", latencies_reads)
print("Write latencies (cycles):", latencies_writes)

# Plot histogram for read latencies and save as in_histogram.png
plt.figure(figsize=(8,6))
plt.hist(latencies_reads, bins=20, color='skyblue', edgecolor='black', alpha=0.75)
plt.title("Read Request Latency Histogram")
plt.xlabel("Latency (cycles)")
plt.ylabel("Frequency")
plt.grid(True, linestyle='--', alpha=0.6)
plt.tight_layout()
plt.savefig("in_histogram.png")
plt.close()

# Plot histogram for write latencies and save as out_histogram.png
plt.figure(figsize=(8,6))
plt.hist(latencies_writes, bins=20, color='salmon', edgecolor='black', alpha=0.75)
plt.title("Write Request Latency Histogram")
plt.xlabel("Latency (cycles)")
plt.ylabel("Frequency")
plt.grid(True, linestyle='--', alpha=0.6)
plt.tight_layout()
plt.savefig("out_histogram.png")
plt.close()
