#ifndef DRAM_MODEL_H
#define DRAM_MODEL_H

#include <cstdint>
#include <unordered_map>
#include "verilated.h"  // For CData and IData

class DRAMModel {
public:
    DRAMModel();
    ~DRAMModel();

    // Processes a memory command and updates the response signals.
    // - request_command: 0 = NO_COMMAND, 1 = READ_COMMAND, 2 = WRITE_COMMAND.
    // - request_addr: The address to read from or write to.
    // - request_data: Data to be written (for a write command).
    // - response_complete: Set to 1 when the DRAM has processed the command.
    // - response_data: A 32-bit response value.
    void update(uint32_t request_command, uint32_t request_addr, uint32_t request_data,
                CData* response_complete, IData* response_data);

    // Preload a memory location with a specific value.
    void set_memory(uint32_t addr, uint32_t value);

private:
    enum DRAMState { DRAM_IDLE, DRAM_WAIT } state;
    uint32_t delay_counter;
    uint32_t current_command;
    uint32_t current_addr;
    uint32_t current_data;

    // Simple memory storage: maps addresses to data.
    std::unordered_map<uint32_t, uint32_t> memory;

    // Constants.
    static const uint32_t NO_COMMAND    = 0;
    static const uint32_t READ_COMMAND  = 1;
    static const uint32_t WRITE_COMMAND = 2;
    static const uint32_t RESPONSE_DELAY = 2; // Number of cycles to delay before issuing a response
};

#endif // DRAM_MODEL_H
