module BankPhysicalMemoryResponsePerformanceStatistics #(
    parameter int RANK,
    parameter int BANK
)(
    input wire clk,
    input wire reset,
    input wire resp_fire,
    input wire [31:0] addr,
    input wire [31:0] data,
    input wire [63:0] globalCycle,
    input wire [31:0] request_id,
    input wire [31:0] active_row,
    input wire [31:0] active_col
);
    integer file;
    reg [1023:0] filename;

    initial begin
        $sformat(filename, "bank_resp_queue_stats_rank%0d_bank%0d.csv", RANK, BANK);
        file = $fopen(filename, "w");
        $fwrite(file, "RequestID,Address,Data,Cycle,Active Row,Active Col\n");
    end

    always @(posedge clk) begin
        if (!reset && resp_fire) begin
            $fwrite(file, "%d,%d,%d,%d,%d,%d\n", request_id, addr, data, globalCycle, active_row, active_col);
        end
    end
endmodule
