#ifndef DRAM_MODEL_H
#define DRAM_MODEL_H

#include <cstdint>
#include <unordered_map>
#include "verilated.h"  // For CData and IData

class DRAMModel {
public:
    DRAMModel();
    ~DRAMModel();

    // Processes a memory command based on active-low control signals.
    // - cs: Chip Select (active low).
    // - ras: Row Access Strobe (active low).
    // - cas: Column Access Strobe (active low).
    // - we: Write Enable (active low; when low, perform a write; when high, perform a read).
    // - addr: The memory address to access.
    // - wdata: Data to be written (if it's a write operation).
    // - response_complete: Set to 1 when the DRAM has processed the command.
    // - response_data: A 32-bit response value.
    void update(CData cs, CData ras, CData cas, CData we,
                IData addr, IData wdata,
                CData* response_complete, IData* response_data);

    // Preload a memory location with a specific value.
    void set_memory(uint32_t addr, uint32_t value);

private:
    enum DRAMState { DRAM_IDLE, DRAM_ACTIVATE, DRAM_WAIT, DRAM_PRECHARGE } state;
    uint32_t delay_counter;
    // current_op: 1 for read, 2 for write.
    uint32_t current_op;
    uint32_t result;
    uint32_t current_addr;
    uint32_t current_data;
    uint32_t refresh_cycle_counter;
    uint32_t memory_activated;

    // Simple memory storage using an unordered_map.
    std::unordered_map<uint32_t, uint32_t> memory;

    // Operation constants.
    static const uint32_t READ_OP  = 1;
    static const uint32_t WRITE_OP = 2;

    // Timing parameters (delays in cycles)
    static const uint32_t tRCD_DELAY = 5;  // Row to column delay
    static const uint32_t tCL_DELAY  = 5;  // CAS latency delay
    static const uint32_t tPRE_DELAY = 10;  // Precharge delay
    static const uint32_t tREFRESH = 10;  // Precharge delay

    // Refresh cycles
    static const uint32_t REFRESH_CYCLES = 200;  // Refresh cycles
};

#endif // DRAM_MODEL_H
