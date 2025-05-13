import argparse
import pandas as pd
import matplotlib.pyplot as plt
import os
from pathlib import Path


def read_csv(path):
    df = pd.read_csv(path, skipinitialspace=True)
    df = df.dropna(subset=["RequestID", "Cycle"])
    df["RequestID"] = df["RequestID"].astype(int)
    df["Cycle"] = df["Cycle"].astype(int)
    return df


def compute_latency(input_df, output_df):
    # Deduplicate output to keep earliest cycle
    output_df = output_df.sort_values("Cycle").drop_duplicates("RequestID", keep="first")

    # Merge on RequestID
    merged = pd.merge(input_df[["RequestID", "Cycle"]], output_df[["RequestID", "Cycle"]],
                      on="RequestID", suffixes=("_in", "_out"))

    merged["Latency"] = merged["Cycle_out"] - merged["Cycle_in"]
    return merged[["Cycle_in", "Latency"]]


def average_latency(df, scale):
    df = df.sort_values("Cycle_in")
    df["bin"] = (df["Cycle_in"] // scale) * scale
    binned = df.groupby("bin")["Latency"].mean().reset_index()
    return binned.rename(columns={"bin": "Cycle", "Latency": "AvgLatency"})


def plot_latency(binned_df, output_path):
    plt.figure(figsize=(10, 6))
    plt.plot(binned_df["Cycle"], binned_df["AvgLatency"], marker="o")
    plt.xlabel("In-Cycle (binned)")
    plt.ylabel("Average Latency")
    plt.title("Latency vs In-Cycle Time")
    plt.grid(True)
    plt.tight_layout()
    plt.savefig(output_path)
    print(f"✅ Saved plot to {output_path}")


def main():
    parser = argparse.ArgumentParser(description="Plot latency vs in-cycle time from directory containing input/output CSVs.")
    parser.add_argument("csv_dir", help="Directory containing input_request_stats.csv and output_request_stats.csv")
    parser.add_argument("--scale", type=int, default=100, help="Binning scale for latency average (default=100)")
    parser.add_argument("--out", default="latency_plot.png", help="Output plot file name")

    args = parser.parse_args()
    csv_dir = Path(args.csv_dir)

    input_csv = csv_dir / "input_request_stats.csv"
    output_csv = csv_dir / "output_request_stats.csv"

    if not input_csv.exists() or not output_csv.exists():
        raise FileNotFoundError("Could not find both 'input_request_stats.csv' and 'output_request_stats.csv' in the provided directory.")

    input_df = read_csv(input_csv)
    output_df = read_csv(output_csv)

    latency_df = compute_latency(input_df, output_df)
    binned_df = average_latency(latency_df, args.scale)
    plot_latency(binned_df, args.out)


if __name__ == "__main__":
    main()
