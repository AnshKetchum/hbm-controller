#!/usr/bin/env python3
import os
import argparse
import subprocess
from pathlib import Path
import shutil
import json

def run_simulation(sim_exe, trace_path, out_dir, csv_dir, cycles):
    trace_name = trace_path.stem
    exp_dir = out_dir / f"hardware_config_{queue_size}"
    meta_dir = exp_dir / "meta"
    exp_dir.mkdir(parents=True, exist_ok=True)
    meta_dir.mkdir(parents=True, exist_ok=True)

    print(f"🧪 Running simulation with queueSize={queue_size} for trace {trace_name}...")

    try:
        # Build 
        os.system("make verilog")
        os.system("make verilator-trace")

        # Run simulation
        subprocess.run([sim_exe, "-t", str(trace_path), "-c", str(cycles)],
                       check=True)
    except subprocess.CalledProcessError:
        print(f"❌ Error while running simulation on {trace_path}")
        return

    shutil.copy(trace_path, exp_dir / trace_path.name)

    for csv_file in csv_dir.glob("*.csv"):
        shutil.copy(csv_file, meta_dir / csv_file.name)

    print(f"✅ Finished simulation for queueSize={queue_size}")

def write_config(queue_size, config_dir):

    # Fork the default config 
    config = {}
    with open(os.path.join(config_dir, "default.json"), "w") as f:
        config = json.load(f)

    assert config != {}, "Unable to load config, it is empty"
    config["queueSize"] = queue_size
    with open(os.path.join(config_dir, "config.json"), "w") as f:
        json.dump(config, f, indent=2)

def main():
    parser = argparse.ArgumentParser(description="Sweep queueSize parameter and run hardware simulations.")
    parser.add_argument("--sim", required=True, help="Path to simulator executable.")
    parser.add_argument("--trace", required=True, help="Path to a single trace file.")
    parser.add_argument("--outdir", required=True, help="Directory to store experiment outputs.")
    parser.add_argument("--csv_dir", required=True, help="Directory where simulator writes CSV outputs.")
    parser.add_argument("--cycles", required=True, type=int, help="Number of simulation cycles.")
    parser.add_argument("--start", required=True, type=int, help="Starting queue size.")
    parser.add_argument("--end", required=True, type=int, help="Ending queue size (inclusive).")
    parser.add_argument("--config_dir", default="src/main/config", help="Path to config.json and default.json file.")

    args = parser.parse_args()

    sim_exe = Path(args.sim).resolve()
    trace_path = Path(args.trace).resolve()
    out_dir = Path(args.outdir).resolve()
    csv_dir = Path(args.csv_dir).resolve()
    config_dir = Path(args.config_dir).resolve()

    if not sim_exe.exists():
        print(f"❌ Simulator not found at {sim_exe}")
        return
    if not trace_path.exists():
        print(f"❌ Trace file not found at {trace_path}")
        return
    if not csv_dir.is_dir():
        print(f"❌ CSV output directory not found at {csv_dir}")
        return
    if not config_dir.is_dir():
        print(f"❌ Config default directory not found at {config_dir}")
        return

    out_dir.mkdir(parents=True, exist_ok=True)

    global queue_size
    queue_size = args.start
    while queue_size <= args.end:
        # Update config.json with new queue size
        write_config(queue_size, config_dir)

        # Run simulation
        print("Running simulations ", queue_size)
        run_simulation(sim_exe, trace_path, out_dir, csv_dir, args.cycles)
        print("Done writing simulations ", queue_size)

        # Exponentially increase
        queue_size *= 2

if __name__ == "__main__":
    main()
