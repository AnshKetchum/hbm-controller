#!/usr/bin/env python3
import re, sys

def convert_memtrace(in_path, out_path):
    cycle = 0
    # match Lackey “L addr,size” or “S addr,size” or “M addr,size”
    pattern = re.compile(r'^\s*([LSM])\s*(?:0x)?([0-9a-fA-F]+),(\d+)')

    with open(in_path) as fin, open(out_path, 'w') as fout:
        for line in fin:
            m = pattern.match(line)
            if not m: continue
            op, addr, size = m.groups()
            size = int(size)
            op_str = 'READ' if op == 'L' else 'WRITE'
            cycle += size
            fout.write(f"0x{addr.upper():<8} {op_str:<5} {cycle}\n")

def main():
    if len(sys.argv) != 3:
        print("Usage: parse_trace.py memtrace.log formatted_trace.txt")
        sys.exit(1)
    convert_memtrace(sys.argv[1], sys.argv[2])
    print(f"👉 Wrote {sys.argv[2]}")

if __name__ == '__main__':
    main()
