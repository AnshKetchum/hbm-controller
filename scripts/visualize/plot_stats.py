#!/usr/bin/env python3
import argparse
import os
import pandas as pd
import matplotlib.pyplot as plt
import numpy as np
import json

def plot_latency_pdf(latencies, label, outpath, num_cycles):
    average_latency = np.mean(latencies)
    p99_latency     = np.percentile(latencies, 99)
    max_latency     = np.max(latencies)
    total_requests  = len(latencies)
    stats_path      = outpath + ".stats.json"

    plt.figure(figsize=(8, 6))
    plt.hist(latencies,
             bins=100,
             density=True,
             color='steelblue',
             edgecolor='black',
             alpha=0.75)

    plt.axvline(average_latency,
                color='green',
                linestyle='--',
                linewidth=2,
                label=f'Average: {average_latency:.1f}')
    plt.axvline(p99_latency,
                color='red',
                linestyle='dashdot',
                linewidth=2,
                label=f'99th Percentile: {p99_latency:.1f}')

    plt.title(f"{label.capitalize()} Latency Distribution")
    plt.xlabel(f"{label}_latency [max: {max_latency} cycles]")
    plt.ylabel("Density")
    plt.legend()
    plt.tight_layout()
    plt.savefig(outpath)
    plt.close()

    stats_dict = {
        f'{label}_latency': {
            'average': float(average_latency),
            'p99': float(p99_latency),
            'max': float(max_latency)
        },
        'num_cycles': num_cycles,
        'bandwidth': total_requests / num_cycles if num_cycles else 0.0,
    }

    # Try computing utilization from *_trace.txt
    dir_path = os.path.dirname(outpath)
    trace_file = next((f for f in os.listdir(dir_path) if f.endswith('_trace.txt')), None)
    if trace_file:
        trace_path = os.path.join(dir_path, trace_file)
        with open(trace_path, 'r') as f:
            total_trace_requests = sum(1 for _ in f)
            if total_trace_requests > 0:
                stats_dict['utilization'] = total_requests / total_trace_requests

    print("Stats", stats_dict)
    with open(stats_path, 'w') as f:
        json.dump(stats_dict, f, indent=2)

def main():
    p = argparse.ArgumentParser(
        description="Plot read/write latency PDFs from request CSVs"
    )
    p.add_argument('dir',
                   help="Directory containing input_request_stats.csv and output_request_stats.csv")
    p.add_argument('--input',
                   help="Input CSV filename (default: input_request_stats.csv)",
                   default='input_request_stats.csv')
    p.add_argument('--output',
                   help="Output CSV filename (default: output_request_stats.csv)",
                   default='output_request_stats.csv')
    p.add_argument('--prefix',
                   help="Filename prefix for the PDFs (default: 'dramsim')",
                   default='dramsim')
    p.add_argument('--num-cycles', type=int, required=True,
                   help="Total number of simulation cycles")

    args = p.parse_args()

    in_csv  = os.path.join(args.dir, args.input)
    out_csv = os.path.join(args.dir, args.output)

    # load and clean
    df_in = pd.read_csv(in_csv, skipinitialspace=True).dropna(subset=['Cycle'])
    df_out = pd.read_csv(out_csv, skipinitialspace=True).dropna(subset=['Cycle'])
    df_in['Cycle'] = pd.to_numeric(df_in['Cycle'], errors='coerce')
    df_out['Cycle'] = pd.to_numeric(df_out['Cycle'], errors='coerce')
    df_in  = df_in.dropna(subset=['Cycle'])
    df_out = df_out.dropna(subset=['Cycle'])

    # merge on RequestID
    merged = pd.merge(df_in,
                      df_out,
                      on='RequestID',
                      suffixes=('_in', '_out'))
    merged['latency'] = merged['Cycle_out'] - merged['Cycle_in']
    merged_out = merged[['RequestID', 'Address_in', 'Read_in', 'Write_in', 'Cycle_in', 'Cycle_out', 'latency']]
    merged_out = merged_out.sort_values('RequestID')  # sort by RequestID
    merged_out.to_csv(os.path.join('merged_transactions.csv'), index=False)

    # split reads/writes
    reads_in   = merged[merged['Read_in']  == 1].sort_values('Cycle_in')
    reads_out  = merged[merged['Read_out'] == 1].sort_values('Cycle_out')
    writes_in  = merged[merged['Write_in'] == 1].sort_values('Cycle_in')
    writes_out = merged[merged['Write_out']== 1].sort_values('Cycle_out')

    # compute latencies
    n_reads  = min(len(reads_in),  len(reads_out))
    n_writes = min(len(writes_in), len(writes_out))

    lat_reads  = (reads_out .iloc[:n_reads]['Cycle_out'].to_numpy()
                - reads_in  .iloc[:n_reads]['Cycle_in'].to_numpy())
    lat_writes = (writes_out.iloc[:n_writes]['Cycle_out'].to_numpy()
                - writes_in .iloc[:n_writes]['Cycle_in'].to_numpy())

    stats_path = os.path.join(args.dir, 'stats.json')

    # plot and store stats
    plot_latency_pdf(lat_reads,
                 'read',
                 os.path.join(args.dir, f"{args.prefix}_histo_read_latency.pdf"),
                 num_cycles=args.num_cycles)

    plot_latency_pdf(lat_writes,
                 'write',
                 os.path.join(args.dir, f"{args.prefix}_histo_write_latency.pdf"),
                 num_cycles=args.num_cycles)

    print(f"→ Read/write latency PDFs and stats.json written to {args.dir}")

if __name__ == '__main__':
    main()
