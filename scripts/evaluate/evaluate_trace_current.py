#!/usr/bin/env python3
import argparse
import subprocess
from pathlib import Path
import shutil
import json

def run_simulation(sim_exe, trace_path, out_dir, csv_dir, cycles, exp_dirs):
    trace_name = trace_path.stem
    exp_dir = out_dir / f"exp_{trace_name}"
    exp_dir.mkdir(parents=True, exist_ok=True)

    print(f"🧪 Running simulation for {trace_name}...")

    try:
        subprocess.run([sim_exe, "-t", str(trace_path), "-c", str(cycles)],
                       check=True)
    except subprocess.CalledProcessError:
        print(f"❌ Error while running simulation on {trace_path}")
        return

    # Copy trace and CSV stats files
    shutil.copy(trace_path, exp_dir / trace_path.name)
    for fname in ["input_request_stats.csv", "output_request_stats.csv"]:
        fpath = csv_dir / fname
        if fpath.exists():
            shutil.copy(fpath, exp_dir / fname)
        else:
            print(f"⚠️  Warning: {fname} not found in {csv_dir} after simulating {trace_name}")

    exp_dirs.append(str(exp_dir.resolve()))

def main():
    parser = argparse.ArgumentParser(description="Evaluate traces using a simulator.")
    parser.add_argument("--sim", required=True, help="Path to the simulator executable.")
    parser.add_argument("--traces", required=True, help="Directory containing trace files.")
    parser.add_argument("--outdir", required=True, help="Directory to write experiment outputs.")
    parser.add_argument("--csv_dir", required=True, help="Directory where simulator writes CSV outputs.")
    parser.add_argument("--cycles", required=True, type=int, help="Number of cycles to run for each trace.")
    args = parser.parse_args()

    sim_exe = Path(args.sim).resolve()
    traces_dir = Path(args.traces).resolve()
    out_dir = Path(args.outdir).resolve()
    csv_dir = Path(args.csv_dir).resolve()

    if not sim_exe.exists():
        print(f"❌ Simulator not found at {sim_exe}")
        return
    if not traces_dir.is_dir():
        print(f"❌ Trace directory not found at {traces_dir}")
        return
    if not csv_dir.is_dir():
        print(f"❌ CSV output directory not found at {csv_dir}")
        return

    out_dir.mkdir(parents=True, exist_ok=True)

    trace_files = list(traces_dir.glob("*.txt"))
    if not trace_files:
        print("❌ No trace files found.")
        return

    exp_dirs = []
    for trace_file in trace_files:
        run_simulation(sim_exe, trace_file, out_dir, csv_dir, args.cycles, exp_dirs)

    # Write breadcrumb.json
    breadcrumb_path = out_dir / "breadcrumb.json"
    with open(breadcrumb_path, 'w') as f:
        json.dump({"experiments": exp_dirs}, f, indent=2)
    print(f"✅ Wrote breadcrumb.json with {len(exp_dirs)} entries to {breadcrumb_path}")

if __name__ == "__main__":
    main()
