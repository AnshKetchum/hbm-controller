#include "VSingleChannelSystem.h"
#include "verilated.h"
#include <cstdlib>
#include <ctime>
#include <iostream>
#include <fstream>
#include <sstream>
#include <vector>
using namespace std;

const int TIMEOUT_CYCLES = 10000;  // Timeout for waiting on valid response
unsigned long long sim_cycle = 0;

struct TraceEntry {
    unsigned int addr;
    bool is_write;
    unsigned long long cycle;
    unsigned int wdata;  // only valid for writes
};

// Tick function: one clock cycle
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
    VSingleChannelSystem* top = new VSingleChannelSystem;
    srand(time(0));

    // reset
    top->reset = 1;
    for (int i = 0; i < 5; i++) tick(top);
    top->reset = 0; tick(top);

    // always ready
    top->io_out_ready = 1;

    // load trace
    auto trace = load_trace("test.trace");

    size_t idx = 0;
    while (idx < trace.size()) {
        auto &e = trace[idx];
        // advance to target cycle
        while (sim_cycle < e.cycle) {
            tick(top);
        }

        // drive signals
        top->io_in_valid = 1;
        top->io_in_bits_addr = e.addr;
        top->io_in_bits_rd_en = !e.is_write;
        top->io_in_bits_wr_en = e.is_write;
        if (e.is_write) top->io_in_bits_wdata = e.wdata;
        tick(top);

        // deassert
        top->io_in_valid = 0;
        tick(top);

        // wait for response
        unsigned long long start = sim_cycle;
        while (!top->io_out_valid && sim_cycle - start < TIMEOUT_CYCLES) {
            tick(top);
        }
        if (!top->io_out_valid) {
            cerr << "Timeout at trace index " << idx << " cycle " << sim_cycle << endl;
            break;
        }

        if (e.is_write) {
            cout << "WRITE ack @" << sim_cycle << " addr=0x" << hex << e.addr << dec << endl;
        } else {
            unsigned int rdata = top->io_out_bits_data;
            cout << "READ resp @" << sim_cycle << " addr=0x" << hex << e.addr
                 << " data=0x" << rdata << dec << endl;
            if (rdata != e.wdata) {
                cout << "ERROR: mismatch at idx " << idx << endl;
            }
        }
        idx++;
    }

    top->final();
    delete top;
    return 0;
}
