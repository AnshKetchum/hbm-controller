#include "dram_model.h"
#include <iostream>

DRAMModel::DRAMModel()
    : state(DRAM_IDLE), delay_counter(0), current_command(0), current_addr(0), current_data(0) {
}

DRAMModel::~DRAMModel() {
}

void DRAMModel::update(uint32_t request_command, uint32_t request_addr, uint32_t request_data,
                       CData* response_complete, IData* response_data) {
    // Clear outputs: response_complete is 1-bit; response_data is a 32-bit value.
    *response_complete = 0;
    *response_data = 0;

    if (state == DRAM_IDLE) {
        if (request_command != NO_COMMAND) {
            // Capture the command and initialize delay.
            current_command = request_command;
            current_addr    = request_addr;
            current_data    = request_data;
            delay_counter   = RESPONSE_DELAY;
            state           = DRAM_WAIT;
        }
    } else if (state == DRAM_WAIT) {
        if (delay_counter > 0) {
            delay_counter--;
        }
        if (delay_counter == 0) {
            uint32_t result = 0;
            if (current_command == READ_COMMAND) {
                // For a read, return the stored value (or 0 if not found).
                auto it = memory.find(current_addr);
                result = (it != memory.end()) ? it->second : 0;
                std::cout << "[DRAM] Reading - " << result << std::endl;
            } else if (current_command == WRITE_COMMAND) {
                // For a write, store the value and return it.
                memory[current_addr] = current_data;
                result = current_data;
            }
            // Directly assign the 32-bit result.
            *response_data = result;
            *response_complete = 1;
            state = DRAM_IDLE;
        }
    }
}

void DRAMModel::set_memory(uint32_t addr, uint32_t value) {
    memory[addr] = value;
}
