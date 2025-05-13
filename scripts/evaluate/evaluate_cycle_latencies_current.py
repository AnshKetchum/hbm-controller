import argparse
import pandas as pd
import matplotlib.pyplot as plt
import seaborn as sns
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
    merged = pd.merge(
        input_df[["RequestID", "Cycle"]],
        output_df[["RequestID", "Cycle"]],
        on="RequestID", suffixes=("_in", "_out")
    )
    merged["Latency"] = merged["Cycle_out"] - merged["Cycle_in"]
    return merged[["Cycle_in", "Latency"]]


def average_latency(df, scale):
    df = df.sort_values("Cycle_in")
    df["bin"] = (df["Cycle_in"] // scale) * scale
    binned = df.groupby("bin")["Latency"].mean().reset_index()
    return binned.rename(columns={"bin": "Cycle", "Latency": "AvgLatency"})


def compute_traffic(df, scale):
    df = df.copy()
    df["bin"] = (df["Cycle_in"] // scale) * scale
    counts = df.groupby("bin").size().reset_index(name="Count")
    return counts.rename(columns={"bin": "Cycle"})


def plot_latency_with_traffic(binned_df, traffic_df, scale, output_path):
    # Seaborn warm theme
    sns.set(style="whitegrid", context="notebook", palette="flare")
    warm_color = sns.color_palette("flare", n_colors=1)[0]

    # Create two stacked subplots with shared x-axis
    fig, (ax1, ax2) = plt.subplots(
        2, 1, sharex=True,
        gridspec_kw={"height_ratios": [3, 1]},
        figsize=(10, 8)
    )

    # Plot average latency line
    ax1.plot(
        binned_df["Cycle"], binned_df["AvgLatency"],
        marker="o", linestyle="-", linewidth=2, color=warm_color
    )
    ax1.set_ylabel("Average Latency", fontsize=12)
    ax1.set_title("Latency vs In-Cycle Time", fontsize=14, fontweight='bold')
    ax1.grid(True, linestyle="--", linewidth=0.5)

    # Plot traffic bar chart
    ax2.bar(
        traffic_df["Cycle"], traffic_df["Count"],
        width=scale * 0.9, alpha=0.6, color=warm_color
    )
    ax2.set_xlabel("In-Cycle (binned)", fontsize=12)
    ax2.set_ylabel("Request Count", fontsize=12)
    ax2.grid(True, linestyle="--", linewidth=0.5)

    plt.tight_layout()
    plt.savefig(output_path, dpi=300)
    print(f"✅ Saved combined plot to {output_path}")


def main():
    parser = argparse.ArgumentParser(
        description="Plot latency vs in-cycle time with traffic bars from directory containing input/output CSVs."
    )
    parser.add_argument("csv_dir", help="Directory containing input_request_stats.csv and output_request_stats.csv")
    parser.add_argument("--scale", type=int, default=100,
                        help="Binning scale for latency average and traffic count (default=100)")
    parser.add_argument("--out", default="latency_with_traffic.png",
                        help="Output plot file name")

    args = parser.parse_args()
    csv_dir = Path(args.csv_dir)

    input_csv = csv_dir / "input_request_stats.csv"
    output_csv = csv_dir / "output_request_stats.csv"

    if not input_csv.exists() or not output_csv.exists():
        raise FileNotFoundError(
            "❌ Could not find both 'input_request_stats.csv' and 'output_request_stats.csv' in the provided directory."
        )

    input_df = read_csv(input_csv)
    output_df = read_csv(output_csv)

    latency_df = compute_latency(input_df, output_df)
    binned_latency = average_latency(latency_df, args.scale)
    traffic_counts = compute_traffic(latency_df, args.scale)

    plot_latency_with_traffic(binned_latency, traffic_counts, args.scale, args.out)


if __name__ == "__main__":
    main()
