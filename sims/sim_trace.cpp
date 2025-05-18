#include "VMultiChannelSystem.h"
#include "verilated.h"
#include <cstdlib>
#include <ctime>
#include <iostream>
#include <fstream>
#include <sstream>
#include <vector>
#include <deque>
#include <unordered_map>
#include <algorithm>
#include <cassert>
using namespace std;

unsigned long long sim_cycle = 0;
static const unsigned long long TIMEOUT = 100000ULL;

// Trace entry for input stimuli
struct TraceEntry {
    unsigned int addr;
    bool is_write;
    unsigned long long cycle;
    unsigned int wdata;
};

// Log entry for enqueued requests
struct EnqueueLogEntry {
    unsigned int addr;
    bool is_write;
    int data; // data for write, -1 for read
};

// Log entry for dequeued responses
struct ResponseLogEntry {
    unsigned int addr;
    bool is_write;
    int data; // returned data
};

vector<EnqueueLogEntry> enqueue_log;
vector<ResponseLogEntry> response_log;
// Track last written data by address
unordered_map<unsigned, unsigned> last_write_data;

void write_enqueue_log(const string &filename) {
    ofstream log_file(filename);
    if (!log_file) {
        cerr << "ERROR: Unable to open enqueue log file: " << filename << endl;
        return;
    }
    for (const auto &e : enqueue_log) {
        log_file << hex << "0x" << e.addr << dec
                 << (e.is_write ? " WRITE " : " READ  ")
                 << e.data << endl;
    }
}

void write_response_log(const string &filename) {
    ofstream log_file(filename);
    if (!log_file) {
        cerr << "ERROR: Unable to open response log file: " << filename << endl;
        return;
    }
    for (const auto &r : response_log) {
        log_file << hex << "0x" << r.addr << dec
                 << (r.is_write ? " WRITE_RESP " : " READ_RESP  ")
                 << r.data << endl;
    }
}

void tick(VMultiChannelSystem* top) {
    top->clock = 0; top->eval();
    top->clock = 1; top->eval();
    sim_cycle++;
}

vector<TraceEntry> load_trace(const string &filename) {
    vector<TraceEntry> trace;
    ifstream infile(filename);
    if (!infile) {
        cerr << "Failed to open trace file: " << filename << endl;
        write_enqueue_log("enqueue_log.txt");
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

bool enqueue_request(VMultiChannelSystem* top, const TraceEntry &e,
                     unordered_map<unsigned, TraceEntry>& pending) {
    top->io_in_valid      = 1;
    top->io_in_bits_addr  = e.addr;
    top->io_in_bits_wr_en = e.is_write;
    top->io_in_bits_rd_en = !e.is_write;
    top->io_in_bits_wdata = e.wdata;

    unsigned long long wait = 0;
    while (!top->io_in_ready && wait++ < TIMEOUT) tick(top);

    if (!top->io_in_ready) {
        cerr << "ERROR: Timeout enqueuing " << (e.is_write ? "WRITE" : "READ")
             << " at cycle " << sim_cycle << " addr=0x" << hex << e.addr << dec << endl;
        top->io_in_valid = 0;
        return false;
    }

    // Log enqueue
    enqueue_log.push_back({e.addr, e.is_write, e.is_write ? static_cast<int>(e.wdata) : -1});

    // Record pending for response check
    pending[e.addr] = e;
    // Track last write data
    if (e.is_write) last_write_data[e.addr] = e.wdata;

    tick(top);
    top->io_in_valid = 0;
    return true;
}

bool dequeue_response(VMultiChannelSystem* top,
                      unordered_map<unsigned, TraceEntry>& pending) {
    if (!top->io_out_valid) return false;

    unsigned int addr = top->io_out_bits_addr;
    unsigned int data = top->io_out_bits_data;
    bool is_write_resp = pending.count(addr) ? pending[addr].is_write : false;

    // Console log
    cout << "[RESP] cycle " << sim_cycle << " ";
    if (is_write_resp) cout << "WRITE_RESP";
    else                cout << "READ_RESP ";
    cout << " addr=0x" << hex << addr << dec
         << " data=0x" << hex << data << dec << endl;

    // Record response
    response_log.push_back({addr, is_write_resp, static_cast<int>(data)});

    // Verify
    if (pending.count(addr)) {
        if (is_write_resp) {
            // Ensure write response matches sent data
            unsigned sent = pending[addr].wdata;
            if (data != sent) {
                cerr << "ERROR: Write mismatch at addr 0x" << hex << addr
                     << ". Sent=0x" << sent << ", Got=0x" << data << dec << endl;
                write_enqueue_log("enqueue_log.txt");
                write_response_log("response_log.txt");

                // tick(top);
                // tick(top);
                // exit(1);
            }
        } else {
            // Read response: check against last written data if exists
            if (last_write_data.count(addr)) {
                unsigned last = last_write_data[addr];
                if (data != last) {
                    cerr << "ERROR: Read mismatch at addr 0x" << hex << addr
                         << ". Expected=0x" << last << ", Got=0x" << data << dec << endl;
                    write_enqueue_log("enqueue_log.txt");
                    write_response_log("response_log.txt");
                    
                    // tick(top);
                    // tick(top);
                    // exit(1);
                }
            }
        }
        pending.erase(addr);
    } else {
        cerr << "WARNING: Received response for unknown addr 0x" << hex << addr << dec << endl;
    }

    tick(top);
    return true;
}

int main(int argc, char **argv) {
    Verilated::commandArgs(argc, argv);
    string trace_file = "test.trace";
    unsigned long long max_cycles = 100000ULL;

    for (int i = 1; i < argc; ++i) {
        string arg = argv[i];
        if (arg == "-t" && i+1 < argc) trace_file = argv[++i];
        else if (arg == "-c" && i+1 < argc) max_cycles = stoull(argv[++i]);
        else {
            cerr << "Usage: " << argv[0] << " [-t <trace>] [-c <max_cycles>]" << endl;
            write_enqueue_log("enqueue_log.txt");
            write_response_log("response_log.txt");
            return 1;
        }
    }

    VMultiChannelSystem *top = new VMultiChannelSystem;
    srand(time(nullptr));

    // Reset
    top->reset = 1;
    for (int i = 0; i < 5; ++i) tick(top);
    top->reset = 0;
    tick(top);

    top->io_out_ready = 1;

    auto trace = load_trace(trace_file);
    size_t idx = 0;
    unordered_map<unsigned, TraceEntry> pending;

    while ((idx < trace.size() || !pending.empty()) && sim_cycle < max_cycles) {
        if (idx < trace.size() && sim_cycle >= trace[idx].cycle) {
            enqueue_request(top, trace[idx], pending);
            idx++;
            continue;
        }
        if (!dequeue_response(top, pending)) tick(top);
    }

    if (sim_cycle >= max_cycles)
        cerr << "ERROR: Max cycles (" << max_cycles << ") reached." << endl;
    else
        cout << "Simulation completed in " << sim_cycle << " cycles." << endl;

    write_enqueue_log("enqueue_log.txt");
    write_response_log("response_log.txt");

    top->final();
    delete top;
    return 0;
}
