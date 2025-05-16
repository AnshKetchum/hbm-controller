#!/usr/bin/env python3
import argparse
from pathlib import Path
import pandas as pd
import matplotlib.pyplot as plt
import numpy as np
import seaborn as sns

# Generic loader: exhaustive search for any CSVs with matching prefix
REQUEST_PREFIX = 'bank_req_queue_stats'
RESPONSE_PREFIX = 'bank_resp_queue_stats'

# Load all bank request entries by scanning files with REQUEST_PREFIX
def load_bank_requests(meta_dir: Path) -> pd.DataFrame:
    records = []
    for path in meta_dir.iterdir():
        if path.is_file() and path.name.startswith(REQUEST_PREFIX) and path.suffix == '.csv':
            df = pd.read_csv(path, skipinitialspace=True)
            if 'RequestID' not in df.columns:
                continue
            for _, r in df.iterrows():
                try:
                    records.append({
                        'RequestID': int(r.RequestID),
                        'Type': r.Type,
                        'Cycle': int(r.Cycle)
                    })
                except Exception:
                    continue
    return pd.DataFrame(records)

# Load all bank response entries by scanning files with RESPONSE_PREFIX
def load_bank_responses(meta_dir: Path) -> pd.DataFrame:
    records = []
    for path in meta_dir.iterdir():
        if path.is_file() and path.name.startswith(RESPONSE_PREFIX) and path.suffix == '.csv':
            df = pd.read_csv(path, skipinitialspace=True)
            if 'RequestID' not in df.columns:
                continue
            for _, r in df.iterrows():
                try:
                    records.append({
                        'RequestID': int(r.RequestID),
                        'Cycle': int(r.Cycle)
                    })
                except Exception:
                    continue
    return pd.DataFrame(records)

# Match command to earliest response after issue
def match_cmd_latencies(cmd_df: pd.DataFrame, resp_df: pd.DataFrame) -> dict:
    types = ['ACTIVATE','READ','WRITE','PRECHARGE','REFRESH']
    lat = {t: [] for t in types}
    if resp_df.empty or cmd_df.empty:
        return {t: 0.0 for t in types}
    resp_group = resp_df.groupby('RequestID')['Cycle'].apply(lambda s: np.sort(s.values))
    for _, row in cmd_df.iterrows():
        rid, typ, issue = int(row.RequestID), row.Type, int(row.Cycle)
        if rid not in resp_group:
            continue
        cycles = resp_group[rid]
        idx = np.searchsorted(cycles, issue + 1)
        if idx < len(cycles):
            lat[typ].append(cycles[idx] - issue)
    return {t: np.mean(lat[t]) if lat[t] else 0.0 for t in types}

# Compute breakdown per experiment
def compute_breakdown(meta_dir: Path) -> dict:
    df_in = pd.read_csv(meta_dir / 'input_request_stats.csv', skipinitialspace=True)
    cmd_df = load_bank_requests(meta_dir)
    resp_df = load_bank_responses(meta_dir)

    # Request queue latency: from input arrival to first ACTIVATE issue
    activate_df = cmd_df[cmd_df['Type'] == 'ACTIVATE']
    if activate_df.empty:
        queue_lat = 0.0
    else:
        first_act = activate_df.groupby('RequestID')['Cycle'].min()
        in_cycles = df_in.set_index('RequestID')['Cycle']
        diffs = (first_act - in_cycles).dropna().values
        queue_lat = np.mean(diffs) if diffs.size else 0.0

    cmd_lats = match_cmd_latencies(cmd_df, resp_df)
    breakdown = {'queue': queue_lat}
    breakdown.update(cmd_lats)
    return breakdown

# Main: gather experiments and plot stacked bar
if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Stacked latency breakdown per experiment")
    parser.add_argument('--outdir', required=True, help='Experiments root dir')
    parser.add_argument('--out', default='breakdown.png', help='Output plot file')
    args = parser.parse_args()

    # Seaborn theming
    sns.set_style('whitegrid')
    palette = sns.color_palette('flare', 6)

    exp_root = Path(args.outdir)
    exps = []
    for exp in exp_root.iterdir():
        if not exp.name.startswith('hardware_config_'):
            continue
        meta = exp / 'meta'
        if not (meta / 'input_request_stats.csv').exists():
            continue
        bd = compute_breakdown(meta)
        exps.append((exp.name, bd))

    # Sort experiments by queue size extracted from folder name
    exps.sort(key=lambda x: int(x[0].split('_')[-1]))

    labels = [e[0] for e in exps]
    cats = ['queue','ACTIVATE','READ','WRITE','PRECHARGE','REFRESH']
    data = np.array([[bd.get(c,0.0) for c in cats] for _, bd in exps])
    perc = data / data.sum(axis=1, keepdims=True) * 100

    fig, ax = plt.subplots(figsize=(12,6))
    bottom = np.zeros(len(exps))
    for i, c in enumerate(cats):
        ax.bar(labels, perc[:,i], bottom=bottom, label=c, color=palette[i])
        bottom += perc[:,i]

    ax.set_ylabel('Latency Breakdown (%)')
    ax.set_title('Stacked Latency Breakdown per Experiment')
    ax.legend(loc='upper right')
    plt.xticks(rotation=45, ha='right')
    plt.tight_layout()
    plt.savefig(args.out)
    print(f"Saved breakdown chart to {args.out}")
