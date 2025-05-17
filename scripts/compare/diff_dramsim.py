#!/usr/bin/env python3
import argparse
import os
import pandas as pd
import numpy as np

def load_latencies(dirpath, prefix='dramsim'):
    """Load and merge input/output CSVs to compute read/write latency arrays.
       Also dumps a merged_transactions.csv to help inspect merge quality.
    """
    in_csv  = os.path.join(dirpath, 'input_request_stats.csv')
    out_csv = os.path.join(dirpath, 'output_request_stats.csv')
    df_in  = pd.read_csv(in_csv,  skipinitialspace=True).dropna(subset=['Cycle'])
    df_out = pd.read_csv(out_csv, skipinitialspace=True).dropna(subset=['Cycle'])

    # ensure numeric
    df_in['Cycle']       = pd.to_numeric(df_in['Cycle'],       errors='coerce')
    df_out['Cycle']      = pd.to_numeric(df_out['Cycle'],      errors='coerce')
    df_in['RequestID']   = pd.to_numeric(df_in['RequestID'],   errors='coerce')
    df_out['RequestID']  = pd.to_numeric(df_out['RequestID'],  errors='coerce')
    df_in  = df_in.dropna(subset=['Cycle', 'RequestID'])
    df_out = df_out.dropna(subset=['Cycle', 'RequestID'])

    # merge
    merged = pd.merge(df_in,
                df_out,
                on='RequestID',
                suffixes=('_in', '_out'))

    # Filter out mismatches between input and output addresses
    merged = merged[merged['Address_in'] == merged['Address_out']]

    # compute latency and save for inspection
    merged['latency'] = merged['Cycle_out'] - merged['Cycle_in']
    merged_out = merged[['RequestID', 'Address_in', 'Read_in', 'Write_in', 'Cycle_in', 'Cycle_out', 'latency']]
    merged_out = merged_out.sort_values('RequestID')  # sort by RequestID
    merged_out.to_csv(os.path.join(dirpath, 'merged_transactions.csv'), index=False)

    # split
    reads_in   = merged[merged['Read_in']   == 1].sort_values('Cycle_in')
    reads_out  = merged[merged['Read_out']  == 1].sort_values('Cycle_out')

    writes_in  = merged[merged['Write_in']  == 1].sort_values('Cycle_in')
    writes_out = merged[merged['Write_out'] == 1].sort_values('Cycle_out')

    # latencies
    n_reads  = min(len(reads_in),  len(reads_out))
    n_writes = min(len(writes_in), len(writes_out))
    lat_reads  = (reads_out.iloc[:n_reads]['Cycle_out'].to_numpy()
                - reads_in.iloc[:n_reads]['Cycle_in'].to_numpy())
    lat_writes = (writes_out.iloc[:n_writes]['Cycle_out'].to_numpy()
                - writes_in.iloc[:n_writes]['Cycle_in'].to_numpy())
    return lat_reads, lat_writes

def summarize_and_write(diffs, outpath):
    """Compute mean, variance, and std dev of diffs, and write to CSV."""
    mean = np.mean(diffs)
    var  = np.var(diffs, ddof=0)
    std  = np.std(diffs, ddof=0)
    df = pd.DataFrame({
        'metric': ['mean', 'variance', 'stddev'],
        'value':  [mean, var, std]
    })
    df.to_csv(outpath, index=False)

def main():
    p = argparse.ArgumentParser(
        description="Compute read/write latency differences between simulators"
    )
    p.add_argument('--current-dir',
                   required=True,
                   help="Directory of your current simulator's CSVs")
    p.add_argument('--baseline-dir',
                   required=True,
                   help="Directory of DRAMSim3's CSVs")
    p.add_argument('--out-dir',
                   default=None,
                   help="Where to write read_diff_stats.csv and write_diff_stats.csv (default=current-dir)")
    args = p.parse_args()

    out_dir = args.out_dir or args.current_dir

    # load both sets
    cur_reads,  cur_writes  = load_latencies(args.current_dir)
    base_reads, base_writes = load_latencies(args.baseline_dir)

    # align lengths
    n_r = min(len(cur_reads), len(base_reads))
    n_w = min(len(cur_writes), len(base_writes))
    read_diffs  = cur_reads[:n_r]  - base_reads[:n_r]
    write_diffs = cur_writes[:n_w] - base_writes[:n_w]

    # summarize + write
    summarize_and_write(read_diffs,
                        os.path.join(out_dir, 'read_diff_stats.csv'))
    summarize_and_write(write_diffs,
                        os.path.join(out_dir, 'write_diff_stats.csv'))

    print(f"→ Wrote read_diff_stats.csv and write_diff_stats.csv to {out_dir}")

if __name__ == '__main__':
    main()
