#include "VSingleChannelSystem.h"
#include "verilated.h"
#include <cstdlib>
#include <ctime>
#include <iostream>
#include <fstream>
#include <sstream>
#include <vector>
#include <deque>
#include <unordered_map>
using namespace std;

unsigned long long sim_cycle = 0;

struct TraceEntry {
    unsigned int addr;
    bool is_write;
    unsigned long long cycle;
    unsigned int wdata;
};

void tick(VSingleChannelSystem* top) {
    top->clock = 0; top->eval();
    top->clock = 1; top->eval();
    sim_cycle++;
}

vector<TraceEntry> load_trace(const string &filename) {
    vector<TraceEntry> trace;
    ifstream infile(filename);
    if (!infile) {
        cerr << "Failed to open trace file: " << filename << endl;
        exit(1);
    }
    string line;
    while (getline(infile, line)) {
        if (line.empty()) continue;
        istringstream iss(line);
        string addr_str, op;
        unsigned long long cycle;
        iss >> addr_str >> op >> cycle;
        TraceEntry e;
        e.addr = stoul(addr_str, nullptr, 16);
        e.is_write = (op == "WRITE");
        e.cycle = cycle;
        e.wdata = e.is_write ? rand() : 0;
        trace.push_back(e);
    }
    return trace;
}

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    string trace_filename = "test.trace";
    unsigned long long max_cycles = 100000;

    // Parse short CLI flags: -t for trace file, -c for cycles
    for (int i = 1; i < argc; ++i) {
        string arg = argv[i];
        if (arg == "-t" && i + 1 < argc) {
            trace_filename = argv[++i];
        } else if (arg == "-c" && i + 1 < argc) {
            max_cycles = stoull(argv[++i]);
        } else {
            cerr << "Usage: " << argv[0] << " [-t <trace_file>] [-c <max_cycles>]" << endl;
            return 1;
        }
    }

    VSingleChannelSystem* top = new VSingleChannelSystem;
    srand(time(0));

    top->reset = 1;
    for (int i = 0; i < 5; i++) tick(top);
    top->reset = 0; tick(top);

    top->io_out_ready = 1;

    auto trace = load_trace(trace_filename);

    size_t idx = 0;
    deque<TraceEntry> pendingReads;
    unordered_map<unsigned int, unsigned int> expectedData;

    while (idx < trace.size() || !pendingReads.empty()) {
        if (sim_cycle >= max_cycles) {
            cout << "ERROR: Simulation timeout after " << sim_cycle << " cycles." << endl;
            break;
        }

        if (idx < trace.size()) {
            auto &e = trace[idx];
            if (sim_cycle >= e.cycle) {
                top->io_in_valid = 1;
                top->io_in_bits_addr = e.addr;
                top->io_in_bits_rd_en = !e.is_write;
                top->io_in_bits_wr_en = e.is_write;
                if (e.is_write) {
                    top->io_in_bits_wdata = e.wdata;
                    expectedData[e.addr] = e.wdata;
                    cout << "[WRITE ] cycle " << sim_cycle << " addr=0x" << hex << e.addr << dec << endl;
                } else {
                    pendingReads.push_back(e);
                    cout << "[READ  ] issued @" << sim_cycle << " addr=0x" << hex << e.addr << dec << endl;
                }

                tick(top);
                top->io_in_valid = 0;
                tick(top);
                idx++;
                continue;
            }
        }

        if (top->io_out_valid) {
            unsigned int raddr = top->io_out_bits_addr;
            unsigned int rdata = top->io_out_bits_data;

            cout << "[RESP  ] cycle " << sim_cycle << " addr=0x" << hex << raddr
                 << " data=0x" << rdata << dec << endl;

            if (expectedData.count(raddr)) {
                unsigned int expected = expectedData[raddr];
                if (rdata != expected) {
                    cout << "ERROR: Mismatch at addr 0x" << hex << raddr
                         << ". Expected 0x" << expected << ", got 0x" << rdata << dec << endl;
                }
                expectedData.erase(raddr);
            } else {
                cout << "WARNING: Unexpected response for addr 0x" << hex << raddr << dec << endl;
            }

            if (!pendingReads.empty()) {
                auto it = find_if(pendingReads.begin(), pendingReads.end(),
                                  [raddr](const TraceEntry &e) { return e.addr == raddr; });
                if (it != pendingReads.end()) pendingReads.erase(it);
            }
        }

        tick(top);
    }

    top->final();
    delete top;
    return 0;
}
