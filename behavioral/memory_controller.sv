/* 
Extremely basic interface for a fledgeling memory controller - 
    + 3/11: With support for (CS, RAS, CAS, WE) decoder signals at a high level

User protocol - 

Read -> 
    (1) set rd_en to high
    (2) set the addr 
    (3) wait for done signal
    (4) retrieve data from rdata signal

Write -> 
    (1) set wr_en to high
    (2) set wdata to high
    (3) wait for done signal
    (4) retrieve data from rdata signal

Memory Controller FSM States

    (1) IDLE => Initial State, when no request is made
    (2) READ => Status while waiting for read response
    (3) WRITE => Status while pending for write response 
    (4) DONE => Transaction is completed, please retrieve the data

*/


// TODO: These parameters aren't actual numbers. Find some good nums.
module memory_controller #(
    parameter tRCD = 5, // Row to column delay, expected when the memory needs to activate a row (activae stages are READ_ISSUE and WRITE _ISSUE)
    parameter tCL = 5, // During the READ_PENDING stage, mem will be accessing the columns, which will incur latency
    parameter tPRE = 10, // During the DONE stage precharging will also take some cycles
    parameter tREFRESH = 10, // Actual delay of a single refresh

    parameter REFRESH_CYCLE_COUNT = 200, // Total number of cycles before we need to refresh the DRAM

    parameter COUNTER_SIZE = 32, // Using a 32 bit counter to track delays
    parameter IDLE_DELAY = 0, // Delays per stage
    parameter READ_ISSUE_DELAY = tRCD, // Delays per stage
    parameter WRITE_ISSUE_DELAY = tRCD,
    parameter READ_PENDING_DELAY = tCL,
    parameter WRITE_PENDING_DELAY = tCL,
    parameter DONE_DELAY = tPRE,
    parameter REFRESH_DELAY = tREFRESH
)(
    input logic clk,
    input logic rst,

    // USER - > CTRLLR
    input logic wr_en,
    input logic rd_en,
    input logic [31:0] addr,
    input logic [31:0] wdata,
    output logic request_rdy,
    input logic request_valid,

    // BEGIN MEM INTERFACE 
    // CTRLLR -> MEM
    output logic [31:0] request_addr,
    output logic [31:0] request_data,
    output logic cs, 
    output logic ras, 
    output logic cas, 
    output logic we,

    // MEM -> CTRLLR
    input logic response_complete,
    input logic [31:0] response_data,

    // CTRLLR -> USER
    output logic [31:0] data,
    output logic done,
    output logic [4:0] ctrllerstate
);
    // Mem Controller States
    localparam  IDLE = 0, 
                READ_PENDING = 1, 
                READ_ISSUE = 2, 
                WRITE_ISSUE = 3,
                WRITE_PENDING = 4,
                DONE = 5,
                REFRESH= 6;
    

    // Track the internal state of the controller
    reg [4:0] state;

    // Track next state / updates
    reg [4:0] next_state;
    reg [31:0] data_to_send;
    wire request_fire;

    // State delay counter. Refresh gets it's own delay counter
    reg [COUNTER_SIZE-1:0] counter;
    reg [COUNTER_SIZE-1:0] next_counter_value;

    reg [COUNTER_SIZE-1:0] refresh_delay_counter;


    /* 
    Next State Logic:
    Signals: (CS, RAS, CAS, WE)

    IDLE -> (1, 0, 0, 0)
    READ_ISSUE -> (0, 0, 1, 1)
    WRITE_ISSUE -> (0, 0, 1, 1)
    READ_PENDING -> (0, 1, 0, 1)
    WRITE_PENDING -> (0, 1, 0, 0)
    DONE -> (0, 0, 1, 0)

    */
    always_comb begin 
        case (state) 
            IDLE: begin 
                // $display("Mem Controller IDLING");
                cs = 1;
                ras = 0;
                cas = 0;
                we = 0;
            end 
            READ_ISSUE: begin 
                // $display("Mem Controller READ ISSUING");
                cs = 0;
                ras = 0;
                cas = 1;
                we = 1;
            end 
            WRITE_ISSUE: begin 
                // $display("Mem Controller WRITE ISSUING %d", counter);
                cs = 0;
                ras = 0;
                cas = 1;
                we = 1;
            end 
            READ_PENDING: begin 
                // $display("Mem Controller READ PENDING");
                cs = 0;
                ras = 1;
                cas = 0;
                we = 1;
            end 
            WRITE_PENDING: begin 
                // $display("Mem Controller WRITE PENDING %h %d", request_addr, request_data);
                cs = 0;
                ras = 1;
                cas = 0;
                we = 0;
            end 
            DONE: begin 
                // $display("Mem Controller PRECHARGING");
                cs = 0;
                ras = 0;
                cas = 1;
                we = 0;
            end 
            REFRESH: begin 
                // $display("Mem Controller REFRESHING");
                cs = 0;
                ras = 0;
                cas = 0;
                we = 1;
            end 
        endcase
    end 

    always_comb begin  
        case (state)
            IDLE: begin 
                next_state = IDLE;
                
                // Handle refreshing logic before anything else
                if(refresh_delay_counter + tREFRESH >= REFRESH_CYCLE_COUNT) begin 
                    // $display("Need to Refresh: %d %d", refresh_delay_counter, REFRESH_CYCLE_COUNT);
                    next_state = REFRESH;
                    next_counter_value = REFRESH_DELAY;
                end 

                else begin 
                    if(rd_en && request_fire) begin 
                        $display("Read.");
                        next_state = READ_ISSUE;
                        next_counter_value = READ_ISSUE_DELAY;
                    end 
                    else if(wr_en && request_fire) begin 
                        $display("Write.");
                        next_state = WRITE_ISSUE;
                        next_counter_value = WRITE_ISSUE_DELAY;
                    end 
                end 
            end 

            // Issue the request to the DRAM 
            READ_ISSUE: begin 
                next_counter_value = READ_PENDING_DELAY;
                next_state = READ_ISSUE;

                if(response_complete || counter == 0) begin 
                    next_state = READ_PENDING;
                end 
            end 

            // Wait for a response from the DRAM 
            READ_PENDING: begin 

                next_counter_value = DONE_DELAY;

                if(response_complete || counter == 0) begin 
                    next_state = DONE;
                    // $display("Received confirmation that the READ response is complete.");
                    // $display("READ Data - %d", data);
                end 
                else next_state = READ_PENDING;
            end 

            // Issue the request to the DRAM 
            WRITE_ISSUE: begin 
                next_counter_value = WRITE_PENDING_DELAY; 

                if(response_complete || counter == 0) begin 
                    next_state = WRITE_PENDING;
                end 
                else next_state = WRITE_ISSUE;
            end 

            // Wait for a response from the DRAM 
            WRITE_PENDING: begin 
                next_counter_value = DONE_DELAY;

                if(response_complete || counter == 0) begin 
                    next_state = DONE;
                    // $display("Received confirmation that the WRITE response is complete.");
                end
                else next_state = WRITE_PENDING;
            end 

            DONE: begin 
                // $display("In Data - %d", data);
                next_counter_value = IDLE_DELAY;

                if(response_complete || counter == 0) begin 
                    next_state = IDLE;
                end 
                else next_state = DONE;
            end 

            REFRESH: begin 
                next_counter_value = IDLE_DELAY;

                if(response_complete || counter == 0) begin 
                    next_state = IDLE;
                    $display("Received confirmation that the REFRESH response is complete.");
                end
                else next_state = REFRESH;
            end 
        endcase
    end

    always_ff @( clk ) begin 
        // $display("Current state %d %d %d %d", state, response_complete, response_data, done);
        if (rst) begin 
            state <= IDLE;
            counter <= 0;
        end 
        else begin 
            if(done) begin 
                $display("DONE - %d", data);
            end

            if(next_state != state) begin 
                // $display("Setting Counter to: %d", next_counter_value);
                counter <= next_counter_value;
                refresh_delay_counter <= (state == REFRESH ? 0 : refresh_delay_counter + 1);
            end else begin 
                // $display("Setting Counter to counter-1: %d", counter - 1);
                counter <= counter - 1;
                refresh_delay_counter <= refresh_delay_counter + 1;
            end 


            state <= next_state; 
        end 
    end

    assign request_addr = addr; 
    assign request_data = wdata; 
    assign request_fire = request_rdy && request_valid;
    assign request_rdy = (state == DONE || state == IDLE); 


    // Set up the memory controller's outputs
    assign done = (state == DONE);
    assign data = response_data;
    assign ctrllerstate = state;
    
endmodule
