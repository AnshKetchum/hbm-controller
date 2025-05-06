#!/usr/bin/env python3
import argparse
import json
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

    # 1) Load both breadcrumbs
    for pth, name in [(curr_bc, "current"), (base_bc, "baseline")]:
        if not pth.exists():
            print(f"❌ {name} breadcrumb.json not found at {pth}")
            return

    curr_map = load_breadcrumb(curr_bc)
    base_map = load_breadcrumb(base_bc)

    # 2) Find the intersection of experiment names
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

        # 3) Verify that the CSVs exist
        for simulator, path in [("current", curr_path), ("baseline", base_path)]:
            for fname in ("input_request_stats.csv", "output_request_stats.csv"):
                f = path / fname
                if not f.exists():
                    print(f"❌ Expected {fname} in {simulator} at {f}, but not found.")
                    return

        # 4) Load, diff, and write
        diff_folder = out_dir / name
        diff_folder.mkdir(parents=True, exist_ok=True)

        cur_reads,  cur_writes  = load_latencies(str(curr_path))
        base_reads, base_writes = load_latencies(str(base_path))

        # align lengths
        n_r = min(len(cur_reads), len(base_reads))
        n_w = min(len(cur_writes), len(base_writes))
        read_diffs  = cur_reads[:n_r]  - base_reads[:n_r]
        write_diffs = cur_writes[:n_w] - base_writes[:n_w]

        # write diff CSVs
        summarize_and_write(read_diffs,
                            str(diff_folder / "read_diff_stats.csv"))
        summarize_and_write(write_diffs,
                            str(diff_folder / "write_diff_stats.csv"))

        diff_exps.append(str(diff_folder.resolve()))

    # 5) Emit top-level breadcrumb.json
    breadcrumb_out = out_dir / "breadcrumb.json"
    with open(breadcrumb_out, 'w') as f:
        json.dump({"experiments": diff_exps}, f, indent=2)

    print(f"\n✅ Wrote diff breadcrumb.json with {len(diff_exps)} entries to {breadcrumb_out}")

if __name__ == "__main__":
    main()
