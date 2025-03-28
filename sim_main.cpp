#include "VSingleChannelSystem.h"
#include "verilated.h"
#include <cstdlib>
#include <ctime>
#include <iostream>
#include <vector>
using namespace std;

const int NUM_TRANSACTIONS = 1000;    // Number of write-read pairs
const int TIMEOUT_CYCLES = 100000;      // Timeout for waiting on valid response

// Global simulation cycle counter
unsigned long long sim_cycle = 0;

// Vectors to store cycle numbers for transactions
vector<unsigned long long> write_req_cycles;
vector<unsigned long long> write_resp_cycles;
vector<unsigned long long> read_req_cycles;
vector<unsigned long long> read_resp_cycles;

// Function to perform a clock cycle
void tick(VSingleChannelSystem* top) {
    top->clock = 0;
    top->eval();
    top->clock = 1;
    top->eval();
    sim_cycle++;  // Increment simulation cycle count for each tick
}

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    VSingleChannelSystem* top = new VSingleChannelSystem;
    
    // Reset sequence
    top->reset = 1;
    for (int i = 0; i < 5; i++) {
        tick(top);
    }
    top->reset = 0;
    tick(top);

    // Seed the random number generator
    srand(time(0));

    // Always accept responses on the output interface
    top->io_out_ready = 1;

    for (int t = 0; t < NUM_TRANSACTIONS; t++) {
        // Generate a random address and data value.
        unsigned int addr  = 0xFFFF;  // limiting address range for example
        unsigned int wdata = rand();

        // ----- Write Transaction -----
        // Drive the input interface for a write.
        top->io_in_valid      = 1;
        top->io_in_bits_wr_en = 1;
        top->io_in_bits_rd_en = 0;
        top->io_in_bits_addr  = addr;
        top->io_in_bits_wdata = wdata;
        
        tick(top);  // Apply one cycle with valid write

        // Record the cycle when the write request was received.
        write_req_cycles.push_back(sim_cycle);

        // Deassert the valid signal.
        top->io_in_valid = 0;
        tick(top);

        // Wait for the write response to be available.
        int cycles = 0;
        while (!top->io_out_valid && cycles < TIMEOUT_CYCLES) {
            tick(top);
            cycles++;
        }
        // Record the cycle when the write response was retrieved.
        write_resp_cycles.push_back(sim_cycle);

        if (cycles == TIMEOUT_CYCLES) {
            cout << "Timeout during write transaction at address 0x" 
                 << hex << addr << dec << endl;
            break;
        }
        cout << "Write completed: Address 0x" << hex << addr 
             << ", Data 0x" << wdata << dec << endl;

        // ----- Read Transaction -----
        // Drive the input interface for a read of the same address.
        top->io_in_valid      = 1;
        top->io_in_bits_wr_en = 0;
        top->io_in_bits_rd_en = 1;
        top->io_in_bits_addr  = addr;
        
        tick(top);  // Apply one cycle with valid read

        // Record the cycle when the read request was received.
        read_req_cycles.push_back(sim_cycle);

        // Deassert the valid signal.
        top->io_in_valid = 0;
        tick(top);

        // Wait for the read response to be available.
        cycles = 0;
        while (!top->io_out_valid && cycles < TIMEOUT_CYCLES) {
            tick(top);
            cycles++;
        }
        // Record the cycle when the read response was retrieved.
        read_resp_cycles.push_back(sim_cycle);

        if (cycles == TIMEOUT_CYCLES) {
            cout << "Timeout during read transaction at address 0x" 
                 << hex << addr << dec << endl;
            break;
        }
        unsigned int rdata = top->io_out_bits_data;
        cout << "Read completed: Address 0x" << hex << addr 
             << ", Expected Data 0x" << wdata 
             << ", Read Data 0x" << rdata << dec << endl;

        if (rdata != wdata) {
            cout << "ERROR: Data mismatch at address 0x" << hex << addr 
                 << ": wrote 0x" << wdata << ", read 0x" << rdata << dec << endl;
        }
    }

    // Print a summary of the cycle numbers for each request and response.
    cout << "\nTransaction Cycle Details:\n" << endl;
    
    cout << "Write Transactions:" << endl;
    cout << "Transaction\tRequest Cycle\tResponse Cycle" << endl;
    for (size_t i = 0; i < write_req_cycles.size(); i++) {
        cout << i << "\t\t" << write_req_cycles[i] << "\t\t" 
             << write_resp_cycles[i] << endl;
    }
    
    cout << "\nRead Transactions:" << endl;
    cout << "Transaction\tRequest Cycle\tResponse Cycle" << endl;
    for (size_t i = 0; i < read_req_cycles.size(); i++) {
        cout << i << "\t\t" << read_req_cycles[i] << "\t\t" 
             << read_resp_cycles[i] << endl;
    }
    
    top->final();
    delete top;
    return 0;
}
