#include "VMultiChannelSystem.h"
#include "verilated.h"
#include <cstdlib>
#include <ctime>
#include <iostream>
#include <unordered_map>
#include <cassert>

using namespace std;

// Number of write-read sanity checks
static const int NUM_TESTS = 25;
// Max cycles to wait for a response before timing out
static const unsigned long long TIMEOUT = 10000ULL;

unsigned long long sim_cycle = 0;

// Advance one clock cycle
void tick(VMultiChannelSystem* top) {
    top->clock = 0;
    top->eval();
    top->clock = 1;
    top->eval();
    sim_cycle++;
}

// Issue a request (read or write) using Decoupled handshake
void issue_request(VMultiChannelSystem* top, bool wr, unsigned int addr, unsigned int wdata) {
    // Drive valid and bits
    top->io_in_valid      = 1;
    top->io_in_bits_wr_en = wr;
    top->io_in_bits_rd_en = !wr;
    top->io_in_bits_addr  = addr;
    top->io_in_bits_wdata = wdata;

    // Wait for ready
    unsigned long long wait = 0;
    while (!top->io_in_ready && wait < TIMEOUT) {
        tick(top);
        wait++;
    }
    if (!top->io_in_ready) {
        cerr << "ERROR: Request enqueue timeout on " << (wr ? "WRITE" : "READ")
             << " @ cycle " << sim_cycle << endl;
        assert(false && "Request enqueue timeout");
        exit(0);
    }

    // Handshake complete: advance one cycle and deassert valid
    tick(top);
    top->io_in_valid = 0;
}

// Wait for and consume a response
unsigned int get_response(VMultiChannelSystem* top, bool expect_wr, unsigned int expected_addr) {
    unsigned long long wait = 0;
    while (!top->io_out_valid && wait < TIMEOUT) {
        tick(top);
        wait++;
    }
    if (!top->io_out_valid) {
        cerr << "ERROR: Response timeout @ cycle " << sim_cycle << endl;
        assert(false && "Response timeout");
    }
    // Optional: check response fields
    unsigned int raddr = top->io_out_bits_addr;
    unsigned int rdata = top->io_out_bits_data;
    bool rwr = top->io_out_bits_wr_en;
    bool rrd = top->io_out_bits_rd_en;

    // Validate response metadata
    assert(raddr == expected_addr && "Response address mismatch");
    assert(rwr == expect_wr && "Response type mismatch");

    // Consume response
    tick(top);

    return rdata;
}

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    
    // Instantiate DUT
    VMultiChannelSystem* top = new VMultiChannelSystem;
    
    // Reset sequence
    top->reset = 1;
    for (int i = 0; i < 5; ++i) tick(top);
    top->reset = 0;
    tick(top);

    // Always ready to accept responses
    top->io_out_ready = 1;

    // Seed RNG
    srand(static_cast<unsigned>(time(nullptr)));

    unordered_map<unsigned int, unsigned int> golden;

    for (int i = 0; i < NUM_TESTS; ++i) {
        unsigned int addr  = rand() % 0x10000; // 16-bit space
        unsigned int wdata = rand();

        // WRITE
        issue_request(top, true, addr, wdata);
        golden[addr] = wdata;
        unsigned int dummy = get_response(top, true, addr);
        (void)dummy;

        // READ
        issue_request(top, false, addr, 0);
        unsigned int rdata = get_response(top, false, addr);

        // Verify data
        unsigned int expected = golden[addr];
        if (rdata != expected) {
            cerr << "ERROR: Data mismatch at addr=0x" << hex << addr
                 << ". Expected=0x" << expected
                 << ", got=0x" << rdata << dec << endl;
            exit(0);
        } else {
            cout << "Test " << i << ": PASS addr=0x" << hex << addr
                 << ", data=0x" << rdata << dec << endl;
        }
    }

    cout << "All " << NUM_TESTS << " sanity tests PASSED!" << endl;

    top->final();
    delete top;
    return 0;
}
