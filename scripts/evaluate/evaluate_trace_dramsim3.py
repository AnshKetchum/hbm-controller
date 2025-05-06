#!/usr/bin/env python3
import argparse
import subprocess
from pathlib import Path
import shutil
import json
import sys

# Import from the plotting script logic
import csv


def convert_dramsim3_json_to_csv(json_path, input_csv, output_csv):
    with open(json_path, 'r') as f:
        json_data = json.load(f)

    input_rows = []
    output_rows = []
    req_id = 0

    for chan, chdat in json_data.items():
        print(chan, chdat)
        if chdat:
            if chdat.get('read_latency'):
                for latency_str, count in chdat.get('read_latency', {}).items():
                    latency = int(latency_str)
                    for _ in range(count):
                        input_rows.append([req_id, 0, 1, 0, 0])
                        output_rows.append([req_id, 0, 1, 0, latency])
                        req_id += 1

            if chdat.get('write_latency'):
                for latency_str, count in chdat.get('write_latency', {}).items():
                    latency = int(latency_str)
                    for _ in range(count):
                        input_rows.append([req_id, 0, 0, 1, 0])
                        output_rows.append([req_id, 0, 0, 1, latency])
                        req_id += 1

    with open(input_csv, 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['RequestID', 'Address', 'Read', 'Write', 'Cycle'])
        w.writerows(input_rows)

    with open(output_csv, 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['RequestID', 'Address', 'Read', 'Write', 'Cycle'])
        w.writerows(output_rows)

    print(f"✅ Wrote {len(input_rows)} input rows to {input_csv}")
    print(f"✅ Wrote {len(output_rows)} output rows to {output_csv}")


def run_simulation(sim_exe, trace_path, config_path, out_dir, csv_dir, cycles, exp_dirs):
    trace_name = trace_path.stem
    exp_dir = out_dir / f"exp_{trace_name}"
    exp_dir.mkdir(parents=True, exist_ok=True)

    print(f"🧪 Running DRAMSim3 for {trace_name}...")

    try:
        subprocess.run([
            str(sim_exe),
            str(config_path),
            "-c", str(cycles),
            "-t", str(trace_path)
        ], check=True)
    except subprocess.CalledProcessError:
        print(f"❌ Error while running simulation on {trace_path}")
        shutil.rmtree(exp_dir, ignore_errors=True)
        return

    # Locate and convert the JSON file
    json_path = csv_dir / "dramsim3.json"
    if not json_path.exists():
        print(f"❌ dramsim3.json not found in {csv_dir}")
        shutil.rmtree(exp_dir, ignore_errors=True)
        return

    input_csv = csv_dir / "input_request_stats.csv"
    output_csv = csv_dir / "output_request_stats.csv"
    try:
        convert_dramsim3_json_to_csv(json_path, input_csv, output_csv)
    except Exception as e:
        print(f"❌ Failed to convert JSON to CSV for {trace_name}: {e}")
        shutil.rmtree(exp_dir, ignore_errors=True)
        return

    # Save results
    shutil.copy(trace_path, exp_dir / trace_path.name)
    for fname in ["input_request_stats.csv", "output_request_stats.csv", "dramsim3.json"]:
        fpath = csv_dir / fname
        if fpath.exists():
            shutil.copy(fpath, exp_dir / fname)
        else:
            print(f"⚠️  Warning: {fname} not found after simulation for {trace_name}")

    exp_dirs.append(str(exp_dir.resolve()))


def main():
    parser = argparse.ArgumentParser(description="Evaluate DRAMSim3 traces.")
    parser.add_argument("--sim", required=True, help="Path to DRAMSim3 executable.")
    parser.add_argument("--dramsim-config", required=True, help="Path to DRAMSim3 config .ini file.")
    parser.add_argument("--traces", required=True, help="Directory containing trace files.")
    parser.add_argument("--outdir", required=True, help="Directory to write experiment outputs.")
    parser.add_argument("--csv_dir", required=True, help="Directory where dramsim3.json is written.")
    parser.add_argument("--cycles", required=True, type=int, help="Number of cycles to run for each trace.")
    args = parser.parse_args()

    sim_exe = Path(args.sim).resolve()
    config_path = Path(args.dramsim_config).resolve()
    traces_dir = Path(args.traces).resolve()
    out_dir = Path(args.outdir).resolve()
    csv_dir = Path(args.csv_dir).resolve()

    for path, desc in [(sim_exe, "Simulator"), (config_path, "DRAMSim3 config"),
                       (traces_dir, "Trace directory"), (csv_dir, "CSV output directory")]:
        if not path.exists():
            print(f"❌ {desc} not found at {path}")
            return

    out_dir.mkdir(parents=True, exist_ok=True)

    trace_files = list(traces_dir.glob("*.txt"))
    if not trace_files:
        print("❌ No trace files found.")
        return

    exp_dirs = []
    for trace_file in trace_files:
        run_simulation(sim_exe, trace_file, config_path, out_dir, csv_dir, args.cycles, exp_dirs)

    breadcrumb_path = out_dir / "breadcrumb.json"
    with open(breadcrumb_path, 'w') as f:
        json.dump({"experiments": exp_dirs}, f, indent=2)
    print(f"✅ Wrote breadcrumb.json with {len(exp_dirs)} entries to {breadcrumb_path}")


if __name__ == "__main__":
    main()
