#!/usr/bin/env python3
import argparse
from pathlib import Path
import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns

def compute_stats(meta_dir: Path) -> tuple[float, float, int, int]:
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
    read_df = df[df["Read"] == 1]
    write_df = df[df["Write"] == 1]

    read_latencies = read_df["Latency"]
    write_latencies = write_df["Latency"]

    read_avg = read_latencies.mean() if not read_latencies.empty else None
    write_avg = write_latencies.mean() if not write_latencies.empty else None

    # Count number of requests
    read_count = len(read_latencies)
    write_count = len(write_latencies)

    return read_avg, write_avg, read_count, write_count


def main():
    parser = argparse.ArgumentParser(description="Compute and plot pareto curve: #requests vs avg latency")
    parser.add_argument("--outdir", required=True, help="Top-level experiments directory (e.g. ./exp)")
    args = parser.parse_args()

    exp_root = Path(args.outdir)
    if not exp_root.is_dir():
        raise SystemExit(f"❌ Directory not found: {exp_root}")
    
    sizes = []  # queue sizes, for reference if needed
    read_counts = []
    write_counts = []
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
        read_avg, write_avg, read_count, write_count = compute_stats(meta_dir)
        print(f"queueSize={qs}: read_avg={read_avg}, write_avg={write_avg}, read_count={read_count}, write_count={write_count}")

        sizes.append(qs)
        read_latencies.append(read_avg)
        write_latencies.append(write_avg)
        read_counts.append(read_count)
        write_counts.append(write_count)

    if not sizes:
        raise SystemExit("❌ No valid experiment folders found.")

    # Sort data
    read_sorted = sorted(zip(read_counts, read_latencies))
    write_sorted = sorted(zip(write_counts, write_latencies))

    r_counts, r_lat = zip(*read_sorted)
    w_counts, w_lat = zip(*write_sorted)

    # Seaborn warm style
    sns.set(style="whitegrid", context="notebook", palette="flare")

    # Get warm colors from flare palette
    warm_colors = sns.color_palette("flare", n_colors=2)
    read_color = warm_colors[0]  # Deep red/orange
    write_color = warm_colors[1]  # Light warm

    # Plot
    plt.figure(figsize=(8, 6))
    plt.plot(r_counts, r_lat, marker='o', linestyle='-', linewidth=2, color=read_color, label='Read')
    plt.plot(w_counts, w_lat, marker='s', linestyle='--', linewidth=2, color=write_color, label='Write')
    plt.xlabel("Number of Requests")
    plt.ylabel("Average Latency (cycles)")
    plt.title("Pareto Curve: #Requests vs Avg Latency", fontsize=14, fontweight='bold')
    plt.legend()
    plt.grid(True, linestyle="--", linewidth=0.5)
    plt.tight_layout()
    plt.savefig('pareto_latency_plot.png', dpi=300)
    plt.show()

if __name__ == "__main__":
    main()
