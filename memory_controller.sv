/* 
Extremely basic interface for a fledgeling memory controller - 

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

`define NO_COMMAND 0
`define READ_COMMAND 1
`define WRITE_COMMAND 2

module memory_controller (
    input logic clk,
    input logic rst,

    // BEGIN USER PROVIDED DATA 
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
    output logic [31:0] request_command,

    // MEM -> CTRLLR
    input logic response_complete,
    input logic [31:0] response_data,

    // BEGIN MEM CONTROLLER OUTPUTS
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
                DONE = 5;
    

    // Track the internal state of the controller
    reg [4:0] state;

    // Track next state / updates
    reg [4:0] next_state;
    reg [31:0] data_to_send;
    wire request_fire;

    always_comb begin  
        case (state)
            IDLE: begin 

                next_state = IDLE;
                if(rd_en && request_fire) begin 
                    next_state = READ_ISSUE;
                end 
                else if(wr_en && request_fire) begin 
                    next_state = WRITE_ISSUE;
                end 
            end 

            // Issue the request to the DRAM 
            READ_ISSUE: begin 
                next_state = READ_PENDING;

            end 

            // Wait for a response from the DRAM 
            READ_PENDING: begin 

                if(response_complete) begin 
                    next_state = DONE;
                    $display("Received confirmation that the READ response is complete.");
                    $display("READ Data - %d", data);
                end 
                else next_state = READ_PENDING;
            end 

            // Issue the request to the DRAM 
            WRITE_ISSUE: begin 

                next_state = WRITE_PENDING;
            end 

            // Wait for a response from the DRAM 
            WRITE_PENDING: begin 

                if(response_complete) begin 
                    next_state = DONE;
                    $display("Received confirmation that the WRITE response is complete.");
                end
                else next_state = WRITE_PENDING;
            end 

            DONE: begin 
                $display("In Data - %d", data);
                next_state = IDLE;
                if(rd_en && request_fire) begin 
                    next_state = READ_ISSUE;
                end 
                else if(wr_en && request_fire) begin 
                    next_state = WRITE_ISSUE;
                end 
            end 
        endcase
    end

    always_ff @( clk ) begin 
        // $display("Current state %d %d %d %d", state, response_complete, response_data, done);
        if (rst) begin 
            state <= IDLE;
        end 
        else begin 
            if(done) begin 
                $display("DONE - %d", data);
            end
            state <= next_state; 
        end 
    end

    // Configure request data to the memory 
    assign request_command = (state == READ_PENDING ? `READ_COMMAND : (
        state == WRITE_PENDING ? `WRITE_COMMAND :
        `NO_COMMAND
    ));
    assign request_addr = addr; 
    assign request_data = wdata; 
    assign request_fire = request_rdy && request_valid;
    assign request_rdy = (state == DONE || state == IDLE); 


    // Set up the memory controller's outputs
    assign done = (state == DONE);
    assign data = response_data;
    assign ctrllerstate = state;
    
endmodule
