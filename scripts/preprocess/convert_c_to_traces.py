import os
import subprocess
import argparse
import tempfile
from parse_trace import convert_memtrace

def compile_c_file(src_path, out_path):
    compile_flags = ["-lm"]
    try:
        subprocess.check_call(['gcc', src_path, *compile_flags ,'-o', out_path])
        return True
    except subprocess.CalledProcessError:
        print(f"[!] Failed to compile {src_path}")
        return False

def run_valgrind(exe_path, trace_path):
    try:
        with open(trace_path, 'w') as trace_file:
            subprocess.check_call([
                'valgrind', '--tool=lackey', '--trace-mem=yes', exe_path
            ], stdout=subprocess.DEVNULL, stderr=trace_file)
        return True
    except subprocess.CalledProcessError:
        print(f"[!] Valgrind failed on {exe_path}")
        return False

def main(folder, output_dir):
    os.makedirs(output_dir, exist_ok=True)

    for root, _, files in os.walk(folder):
        for f in files:
            if not f.endswith('.c'):
                continue
            full_path = os.path.join(root, f)
            base = os.path.splitext(os.path.basename(f))[0]
            exe_path = os.path.join(tempfile.gettempdir(), f"{base}_bin")
            trace_raw_path = os.path.join(output_dir, f"{base}_valgrind_raw.log")
            final_trace_path = os.path.join(output_dir, f"{base}_trace.txt")

            print(f"[*] Processing {f}...")

            if not compile_c_file(full_path, exe_path):
                continue
            if not run_valgrind(exe_path, trace_raw_path):
                continue

            convert_memtrace(trace_raw_path, final_trace_path)
            print(f"[+] Trace written to {final_trace_path}")

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Build C programs and extract memory traces.")
    parser.add_argument('folder', help="Folder containing C source files")
    parser.add_argument('--output', '-o', default='traces', help="Output directory for traces")
    args = parser.parse_args()
    main(args.folder, args.output)
