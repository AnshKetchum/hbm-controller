`timescale 1ns/1ps

module tb_sdram_controller;

    // Clock and reset signals
    reg         clk;
    reg         reset;

    // Memory controller interface signals
    reg  [15:0] write_data;
    wire [15:0] read_data;
    reg  [21:0] address;
    reg         read;
    reg         write;
    wire        busy;

    // SDRAM interface signals
    wire        sdram_cs;
    wire        sdram_ras;
    wire        sdram_cas;
    wire        sdram_we;
    wire [12:0] sdram_addr;
    wire [1:0]  sdram_bank;
    wire [15:0] sdram_data;

    // Instantiate the SDRAM controller
    sdram_controller uut (
        .clk(clk),
        .reset(reset),
        .write_data(write_data),
        .read_data(read_data),
        .address(address),
        .read(read),
        .write(write),
        .busy(busy),
        .sdram_cs(sdram_cs),
        .sdram_ras(sdram_ras),
        .sdram_cas(sdram_cas),
        .sdram_we(sdram_we),
        .sdram_addr(sdram_addr),
        .sdram_bank(sdram_bank),
        .sdram_data(sdram_data)
    );

    // Instantiate the SDRAM model
    sdram_model sdram_inst (
        .clk(clk),
        .reset(reset),
        .cs(sdram_cs),
        .ras(sdram_ras),
        .cas(sdram_cas),
        .we(sdram_we),
        .addr(sdram_addr),
        .bank(sdram_bank),
        .data(sdram_data),
        .ready()  // Not used here, but could be monitored if needed.
    );

    // Clock generation: 10 ns period (100 MHz clock)
    initial begin
        clk = 0;
        forever #5 clk = ~clk;
    end

    // Task to wait for busy flag to deassert with timeout checking.
    task wait_for_not_busy;
        integer timeout;
        begin
            timeout = 0;
            while (busy && timeout < 1000) begin
                #10;
                timeout = timeout + 1;
            end
            if (timeout == 1000) begin
                $display("ERROR: Timeout waiting for busy flag to deassert at time %t", $time);
                $fatal;
            end
        end
    endtask

    // Main test stimulus
    initial begin
        // Setup waveform dump for GTKWave
        $dumpfile("waveform.vcd");
        $dumpvars(0, tb_sdram_controller);

        // Initialize signals
        reset      = 1;
        write_data = 16'h0000;
        address    = 22'h0;
        read       = 0;
        write      = 0;

        // Hold reset for a few clock cycles
        #20;
        reset = 0;
        $display("INFO: Released reset at time %t", $time);

        // --- Write Operation ---
        // Write data 0xA5A5 to address 0x000001
        write_data = 16'hA5A5;
        address    = 22'h000001;
        write      = 1;
        #10;
        write      = 0;
        $display("INFO: Initiated write operation at time %t", $time);
        
        // Wait for the controller to complete the transaction
        wait_for_not_busy();
        $display("INFO: Write operation completed at time %t", $time);

        // --- Read Operation ---
        // Read from address 0x000001
        address = 22'h000001;
        read    = 1;
        #10;
        read    = 0;
        $display("INFO: Initiated read operation at time %t", $time);
        
        // Wait for the controller to complete the transaction
        wait_for_not_busy();

        // Assertion check: verify that read_data equals 0xA5A5.
        if (read_data !== 16'hA5A5) begin
            $display("ERROR: Assertion failed at time %t - expected 0xA5A5, got 0x%h", $time, read_data);
            $fatal;
        end else begin
            $display("INFO: Assertion passed at time %t - read_data equals 0xA5A5", $time);
        end

        $display("INFO: All tests completed successfully at time %t", $time);
        #50;
        $finish;
    end

endmodule
