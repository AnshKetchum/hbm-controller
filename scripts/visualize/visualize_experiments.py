#!/usr/bin/env python3
import argparse
import os
import json
import pandas as pd
from plot_stats import plot_latency_pdf  # Ensure this supports num_cycles

def process_experiment(experiment_dir, prefix, num_cycles):
    # Paths to input and output CSV files in the experiment directory
    in_csv  = os.path.join(experiment_dir, 'input_request_stats.csv')
    out_csv = os.path.join(experiment_dir, 'output_request_stats.csv')

    # Load the CSV files into pandas DataFrames
    df_in = pd.read_csv(in_csv, skipinitialspace=True).dropna(subset=['Cycle'])
    df_out = pd.read_csv(out_csv, skipinitialspace=True).dropna(subset=['Cycle'])
    df_in['Cycle'] = pd.to_numeric(df_in['Cycle'], errors='coerce')
    df_out['Cycle'] = pd.to_numeric(df_out['Cycle'], errors='coerce')
    df_in  = df_in.dropna(subset=['Cycle'])
    df_out = df_out.dropna(subset=['Cycle'])

    # Merge the data based on RequestID
    merged = pd.merge(df_in, df_out, on='RequestID', suffixes=('_in', '_out'))
    merged['latency'] = merged['Cycle_out'] - merged['Cycle_in']
    merged_out = merged[['RequestID', 'Address_in', 'Read_in', 'Write_in', 'Cycle_in', 'Cycle_out', 'latency']]
    merged_out = merged_out.sort_values('RequestID')  # sort by RequestID
    merged_out.to_csv(os.path.join(experiment_dir, 'merged_transactions.csv'), index=False)

    # Split the reads and writes
    reads_in   = merged[merged['Read_in']  == 1].sort_values('Cycle_in')
    reads_out  = merged[merged['Read_out'] == 1].sort_values('Cycle_out')
    writes_in  = merged[merged['Write_in'] == 1].sort_values('Cycle_in')
    writes_out = merged[merged['Write_out']== 1].sort_values('Cycle_out')

    # Compute the latencies for reads and writes
    n_reads  = min(len(reads_in),  len(reads_out))
    n_writes = min(len(writes_in), len(writes_out))

    lat_reads  = (reads_out .iloc[:n_reads]['Cycle_out'].to_numpy()
                - reads_in  .iloc[:n_reads]['Cycle_in'].to_numpy())
    lat_writes = (writes_out.iloc[:n_writes]['Cycle_out'].to_numpy()
                - writes_in .iloc[:n_writes]['Cycle_in'].to_numpy())

    # Plot and save latency PDFs
    plot_latency_pdf(lat_reads, 'read', os.path.join(experiment_dir, f"{prefix}_histo_read_latency.pdf"), num_cycles = num_cycles)
    plot_latency_pdf(lat_writes, 'write', os.path.join(experiment_dir, f"{prefix}_histo_write_latency.pdf"), num_cycles = num_cycles)

    print(f"→ Latency histograms saved to {experiment_dir}")

def main():
    p = argparse.ArgumentParser(
        description="Plot latency histograms for each experiment from breadcrumb.json"
    )
    p.add_argument('experiment_directory',
                   help="Top-level directory containing breadcrumb.json and experiment directories")
    p.add_argument('--prefix',
                   help="Filename prefix for the PDFs (default: 'dramsim')",
                   default='dramsim')
    p.add_argument('--num-cycles', type=int,
                   help="Total number of simulation cycles to normalize histogram",
                   required=True)
    args = p.parse_args()

    # Load the breadcrumb.json file
    breadcrumb_file = os.path.join(args.experiment_directory, 'breadcrumb.json')
    with open(breadcrumb_file, 'r') as f:
        breadcrumb = json.load(f)

    # Iterate over all experiments listed in breadcrumb.json
    for experiment in breadcrumb['experiments']:
        experiment_dir = os.path.join(args.experiment_directory, experiment)
        if os.path.isdir(experiment_dir):
            print(f"Processing experiment: {experiment_dir}")
            process_experiment(experiment_dir, args.prefix, args.num_cycles)
        else:
            print(f"Warning: Experiment directory not found: {experiment_dir}")

if __name__ == '__main__':
    main()
