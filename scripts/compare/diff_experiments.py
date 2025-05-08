#!/usr/bin/env python3
import argparse
import json
import pandas as pd
import numpy as np
from pathlib import Path
from diff_dramsim import load_latencies, summarize_and_write

def load_breadcrumb(bc_path):
    """
    Read breadcrumb.json and return a dict mapping experiment folder name
    to its full Path.
    """
    with open(bc_path, 'r') as f:
        data = json.load(f)
    mapping = {}
    for exp_path in data.get("experiments", []):
        p = Path(exp_path)
        if not p.is_dir():
            print(f"⚠️  Skipping non-directory in breadcrumb: {p}")
            continue
        mapping[p.name] = p
    return mapping

def find_stats_json(dirpath: Path, kind: str):
    """
    Return the stats.json for 'read' or 'write' in dirpath, or None.
    Looks for filenames ending with:
      - '*_read_latency.pdf.stats.json'
      - '*_write_latency.pdf.stats.json'
    """
    suffix = f"{kind}_latency.pdf.stats.json"
    for f in dirpath.iterdir():
        if f.suffix == ".json" and f.name.endswith(suffix):
            return f
    return None

def append_bw_util_diff(csv_path: Path, bw_diff: float, util_diff: float):
    """
    Read existing diff-stats CSV, append bandwidth_diff and utilization_diff rows,
    and overwrite the CSV.
    """
    df = pd.read_csv(csv_path)
    df = df._append([
        {"metric": "bandwidth_diff",   "value": bw_diff},
        {"metric": "utilization_diff", "value": util_diff}
    ], ignore_index=True)
    df.to_csv(csv_path, index=False)

def main():
    parser = argparse.ArgumentParser(
        description="Compute latency diffs between current simulator and DRAMSim3"
    )
    parser.add_argument(
        "--current-dir", required=True,
        help="Directory containing current-sim breadcrumb.json"
    )
    parser.add_argument(
        "--baseline-dir", required=True,
        help="Directory containing DRAMSim3 breadcrumb.json"
    )
    parser.add_argument(
        "--out-dir", required=True,
        help="Where to write per-experiment diffs and new breadcrumb.json"
    )
    args = parser.parse_args()

    curr_bc = Path(args.current_dir)  / "breadcrumb.json"
    base_bc = Path(args.baseline_dir) / "breadcrumb.json"
    out_dir = Path(args.out_dir)

    # 1) Ensure both breadcrumbs exist
    for pth, name in [(curr_bc, "current"), (base_bc, "baseline")]:
        if not pth.exists():
            print(f"❌ {name} breadcrumb.json not found at {pth}")
            return

    curr_map = load_breadcrumb(curr_bc)
    base_map = load_breadcrumb(base_bc)

    # 2) Intersection of experiment names
    common = sorted(set(curr_map) & set(base_map))
    if not common:
        print("❌ No matching experiments found between current and baseline.")
        return

    out_dir.mkdir(parents=True, exist_ok=True)
    diff_exps = []

    print("🔍 Found matching experiments:")
    for name in common:
        curr_path = curr_map[name]
        base_path = base_map[name]
        print(f" • {name}")
        print(f"     current : {curr_path}")
        print(f"     baseline: {base_path}")

        # 3) Verify required CSVs exist
        for simulator, path in [("current", curr_path), ("baseline", base_path)]:
            for fname in ("input_request_stats.csv", "output_request_stats.csv"):
                if not (path / fname).exists():
                    print(f"❌ Expected {fname} in {simulator} at {path / fname}, but not found.")
                    return

        # 4) Load latencies & compute diffs
        cur_reads,  cur_writes  = load_latencies(str(curr_path))
        base_reads, base_writes = load_latencies(str(base_path))

        n_r = min(len(cur_reads), len(base_reads))
        n_w = min(len(cur_writes), len(base_writes))
        read_diffs  = cur_reads[:n_r]  - base_reads[:n_r]
        write_diffs = cur_writes[:n_w] - base_writes[:n_w]

        # 5) Write per-experiment diff CSVs
        diff_folder = out_dir / name
        diff_folder.mkdir(parents=True, exist_ok=True)
        read_csv  = diff_folder / "read_diff_stats.csv"
        write_csv = diff_folder / "write_diff_stats.csv"

        summarize_and_write(read_diffs,  str(read_csv))
        summarize_and_write(write_diffs, str(write_csv))

        # 6) Append bandwidth/utilization diffs separately for read & write
        for kind, diff_csv in [('read', read_csv), ('write', write_csv)]:
            cur_stats_file  = find_stats_json(curr_path, kind)
            base_stats_file = find_stats_json(base_path, kind)
            if cur_stats_file and base_stats_file:
                cur_s  = json.loads(cur_stats_file.read_text())
                base_s = json.loads(base_stats_file.read_text())

                bw_cur   = cur_s.get("bandwidth",   np.nan)
                util_cur = cur_s.get("utilization", np.nan)
                bw_base   = base_s.get("bandwidth",   np.nan)
                util_base = base_s.get("utilization", np.nan)

                bw_diff   = bw_cur   - bw_base
                util_diff = util_cur - util_base

                append_bw_util_diff(diff_csv, bw_diff, util_diff)
            else:
                print(f"⚠️  Skipping {kind} bandwidth/util diffs for {name} (missing {kind}_latency.stats.json)")

        diff_exps.append(str(diff_folder.resolve()))

    # 7) Emit a new breadcrumb.json for the diffs
    breadcrumb_out = out_dir / "breadcrumb.json"
    with open(breadcrumb_out, 'w') as f:
        json.dump({"experiments": diff_exps}, f, indent=2)

    print(f"\n✅ Wrote diff breadcrumb.json with {len(diff_exps)} entries to {breadcrumb_out}")

if __name__ == "__main__":
    main()
