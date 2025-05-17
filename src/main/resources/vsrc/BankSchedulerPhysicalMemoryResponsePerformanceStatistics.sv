module BankSchedulerPhysicalMemoryResponsePerformanceStatistics #(
    parameter int RANK,
    parameter int BANK
)(
    input wire clk,
    input wire reset,
    input wire resp_fire,
    input wire [31:0] addr,
    input wire [31:0] data,
    input wire [63:0] globalCycle,
    input wire [31:0] request_id
);
    integer file;
    reg [1023:0] filename;

    initial begin
        $sformat(filename, "memory_response_queue_stats_scheduler_rank%0d_bank%0d.csv", RANK, BANK);
        file = $fopen(filename, "w");
        $fwrite(file, "RequestID,Address,Cycle,Data\n");
    end

    always @(posedge clk) begin
        if (!reset && resp_fire) begin
            $fwrite(file, "%d,%d,%d,%d\n", request_id, addr, globalCycle, data);
        end
    end
endmodule
