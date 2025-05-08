module BankPhysicalMemoryRequestPerformanceStatistics #(
    parameter int RANK = 0,
    parameter int BANKGROUP = 0,
    parameter int BANK = 0 
)(
    input wire clk,
    input wire reset,
    input wire req_fire,
    input wire [31:0] addr,
    input wire [31:0] data,
    input wire cs,
    input wire ras,
    input wire cas,
    input wire we,
    input wire [63:0] globalCycle,
    input wire [31:0] request_id
);
    integer file;
    reg [1023:0] filename;
    
    initial begin
        $sformat(filename, "bank_req_queue_stats_rank%d_bg%d_bank%d.csv", RANK, BANKGROUP, BANK);
        file = $fopen(filename, "w");
        $fwrite(file, "RequestID,Address,Type,Cycle\n");
    end


    always @(posedge clk) begin
        if (reset) begin
        end else if (req_fire) begin
            if(cs == 0 && ras == 0 && cas == 0 && we == 1) begin 
                $fwrite(file, "%d,%d,%s,%d\n", request_id, addr, "REFRESH", globalCycle);
            end
            else if(cs == 0 && ras == 0 && cas == 1 && we == 0) begin 
                $fwrite(file, "%d,%d,%s,%d\n", request_id, addr, "PRECHARGE", globalCycle);
            end 
            else if(cs == 0 && ras == 0 && cas == 1 && we == 1) begin 
                $fwrite(file, "%d,%d,%s,%d\n", request_id, addr, "ACTIVATE", globalCycle);
            end 
            else if(cs == 0 && ras == 1 && cas == 0 && we == 1) begin 
                $fwrite(file, "%d,%d,%s,%d\n", request_id, addr, "READ", globalCycle);
            end 
            else if(cs == 0 && ras == 1 && cas == 0 && we == 0) begin 
                $fwrite(file, "%d,%d,%s,%d\n", request_id, addr, "WRITE", globalCycle);
            end 
        end
    end
endmodule
