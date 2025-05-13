#!/usr/bin/env python3
import argparse
from pathlib import Path
import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns

def compute_avg_latencies(meta_dir: Path) -> tuple[float, float]:
    df_in = pd.read_csv(meta_dir / "input_request_stats.csv")
    df_out = pd.read_csv(meta_dir / "output_request_stats.csv")

    # Use the earliest output cycle per RequestID
    df_out_min = df_out.groupby("RequestID", as_index=False).agg({"Cycle": "min"})
    df_out_min.rename(columns={"Cycle": "OutCycle"}, inplace=True)

    # Merge input and output data
    df = pd.merge(
        df_in[["RequestID", "Cycle", "Read", "Write"]].rename(columns={"Cycle": "InCycle"}),
        df_out_min,
        on="RequestID",
        how="inner"
    )
    
    df["Latency"] = df["OutCycle"] - df["InCycle"]

    # Separate by type
    read_latencies = df[df["Read"] == 1]["Latency"]
    write_latencies = df[df["Write"] == 1]["Latency"]

    read_avg = read_latencies.mean() if not read_latencies.empty else None
    write_avg = write_latencies.mean() if not write_latencies.empty else None

    return read_avg, write_avg

def main():
    parser = argparse.ArgumentParser(description="Compute and plot read/write average latency vs queueSize")
    parser.add_argument("--outdir", required=True, help="Top-level experiments directory (e.g. ./exp)")
    args = parser.parse_args()

    exp_root = Path(args.outdir)
    if not exp_root.is_dir():
        raise SystemExit(f"❌ Directory not found: {exp_root}")
    
    sizes = []
    read_latencies = []
    write_latencies = []

    for exp_dir in sorted(exp_root.iterdir()):
        name = exp_dir.name
        if not name.startswith("hardware_config_"):
            continue
        try:
            qs = int(name.split("_")[-1])
        except ValueError:
            continue

        meta_dir = exp_dir / "meta"
        if not (meta_dir / "input_request_stats.csv").exists() or not (meta_dir / "output_request_stats.csv").exists():
            print(f"⚠️ Skipping {name}: missing CSVs")
            continue

        print(f"📂 Processing {meta_dir}")
        read_avg, write_avg = compute_avg_latencies(meta_dir)
        print(f"queueSize={qs}: read_avg={read_avg}, write_avg={write_avg}")
        
        sizes.append(qs)
        read_latencies.append(read_avg)
        write_latencies.append(write_avg)

    if not sizes:
        raise SystemExit("❌ No valid experiment folders found.")

    # Sort by queue size
    zipped = sorted(zip(sizes, read_latencies, write_latencies))
    sizes, read_latencies, write_latencies = zip(*zipped)

    # Set Seaborn warm style
    sns.set(style="whitegrid", context="notebook", palette="flare")
    warm_colors = sns.color_palette("flare", n_colors=2)
    read_color = warm_colors[0]
    write_color = warm_colors[1]

    # Plot
    plt.figure(figsize=(8, 6))
    plt.plot(sizes, read_latencies, marker='o', linestyle='-', linewidth=2,
             color=read_color, label='Read Latency')
    plt.plot(sizes, write_latencies, marker='s', linestyle='--', linewidth=2,
             color=write_color, label='Write Latency')
    plt.xscale('log', base=2)
    plt.xlabel("Queue Size", fontsize=12)
    plt.ylabel("Average Latency (cycles)", fontsize=12)
    plt.title("Read/Write Latency vs Queue Size", fontsize=14, fontweight='bold')
    plt.legend()
    plt.grid(True, which="both", linestyle="--", linewidth=0.5)
    plt.tight_layout()
    plt.savefig('read_write_vis.png', dpi=300)
    plt.show()

if __name__ == "__main__":
    main()
