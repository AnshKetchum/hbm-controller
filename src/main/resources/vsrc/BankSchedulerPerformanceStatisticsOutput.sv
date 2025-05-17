module BankSchedulerPerformanceStatisticsOutput #(
    parameter int RANK,
    parameter int BANK
)(
    input wire clk,
    input wire reset,
    input wire resp_fire,
    input wire rd_en,
    input wire wr_en,
    input wire [31:0] addr,
    input wire [63:0] globalCycle,
    input wire [31:0] request_id
);
    integer file;
    reg [1023:0] filename;

    initial begin
        $sformat(filename, "output_response_stats_scheduler_rank%0d_bank%0d.csv", RANK, BANK);
        file = $fopen(filename, "w");
        $fwrite(file, "RequestID,Address,Type,Cycle\n");
    end


    always @(posedge clk) begin
        if (reset) begin
        end else if (resp_fire) begin
            $fwrite(file, "%d,%d,%d,%d,%d\n", request_id, addr,rd_en, wr_en, globalCycle);
        end
    end
endmodule
