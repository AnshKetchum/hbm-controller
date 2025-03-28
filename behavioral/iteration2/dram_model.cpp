#include "dram_model.h"
#include <iostream>

DRAMModel::DRAMModel()
    : state(DRAM_IDLE), delay_counter(0), current_op(0), current_addr(0), current_data(0), refresh_cycle_counter(0), memory_activated(0) {
}

DRAMModel::~DRAMModel() {
}

void DRAMModel::update(CData cs, CData ras, CData cas, CData we,
                       IData addr, IData wdata,
                       CData* response_complete, IData* response_data) {

    /* Tackle Refresh Logic First */
    refresh_cycle_counter++;

    if(refresh_cycle_counter == REFRESH_CYCLES) { // If we hit the cycle count, we will penalize the memory controller
        refresh_cycle_counter = 0;

        // Corrupt all data
        for(auto &item : memory) {
            memory[item.first] = -1;
        }

        memory_activated = 0;
    }


    // Clear outputs: response_complete is 1-bit; response_data is a 32-bit value.
    *response_complete = 0;
    *response_data = 0;

    // Only consider a command if the DRAM is selected.
    // (Active low: cs, ras, and cas must be 0.)
    if (cs == 1 && ras == 0 && cas == 0) {
        // Determine the operation based on WE.
        // Active low: if we == 0 then it's a write; if we == 1 then it's a read.
        delay_counter = -1;
        // std::cout << "[DRAM] LAUNCHING OPERATION" << std::endl;
    }

    else if(cs == 0 && ras == 0 && cas == 0 && we == 1) {
        if(delay_counter == -1) {
            delay_counter = tREFRESH;
            // std::cout << "[DRAM] LAUNCHING REFRESH OPERATION" << std::endl;
        }

        if (delay_counter > 0) {
            delay_counter--;
            // std::cout << "[DRAM] WAITING FOR REFRESH TO COMPLETE." << std::endl;
            *response_complete = 0;
        }
        else {
            // std::cout << "[DRAM] REFRESHING NOW" << std::endl;
            refresh_cycle_counter = 0;
            memory_activated = 0; 
            delay_counter = -1;
            *response_complete = 1;
        }

    }

    else if(cs == 0 && ras == 0 && cas == 1 && we == 1) {
        if(delay_counter == -1) {
            delay_counter = tRCD_DELAY;
        }

        if (delay_counter > 0) {
            delay_counter--;
            // std::cout << "[DRAM] WAITING FOR TRANSACTION TO COMPLETE." << std::endl;
            *response_complete = 0;
        }
        else {
            // std::cout << "[DRAM] ACTIVATING NOW" << std::endl;
            memory_activated = 1; 
            delay_counter = -1;
            *response_complete = 1;
        }
    }

    // We will not allow the Mem Controller to proceed unless the memory has been activated
    else if (cs == 0 && ras == 1 && cas == 0 && memory_activated == 1) {
        if(delay_counter == -1) {
            delay_counter = tCL_DELAY;
        }
        if (delay_counter > 0) {
            delay_counter--;
            // std::cout << "[DRAM] WAITING FOR TRANSACTION TO COMPLETE." << std::endl;
            *response_complete = 0;
        }
        else if (delay_counter == 0) {
            uint32_t result = 0;
            if (we == 1) {
                // For a read, fetch the stored value (or 0 if not found).
                auto it = memory.find(addr);
                result = (it != memory.end()) ? it->second : 0;
                // std::cout << "[DRAM] Reading from address 0x" << std::hex << addr 
                //           << " - " << std::dec << result << std::endl;
            } else if (we == 0) {
                // For a write, store the value and return it.
                memory[addr] = wdata;
                result = wdata;
                std::cout << "[DRAM] Writing " << result << " to address 0x" << std::hex << addr << std::dec << std::endl;
            } else {
                // std::cout << "[DRAM] Somehow passed through." << std::endl;
            }
            // Produce the 32-bit result.
            *response_data = result;
            *response_complete = 1;
            delay_counter = -1;
        }
    }

    else if(cs == 0 && ras == 0 && cas == 1 && we == 0) {
        if(delay_counter == -1) {
            delay_counter = tPRE_DELAY;
        }
        if (delay_counter > 0) {
            delay_counter--;
            // std::cout << "[DRAM] WAITING FOR PRECHARGE TO COMPLETE." << std::endl;
            *response_complete = 0;
        }
        else if(delay_counter == 0) {
            delay_counter = -1;
            memory_activated = 0;
            *response_complete = 1;
        }
    }
}

void DRAMModel::set_memory(uint32_t addr, uint32_t value) {
    memory[addr] = value;
}
