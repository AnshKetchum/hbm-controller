module PerformanceStatisticsOutput(
    input wire clk,
    input wire reset,
    input wire resp_fire,
    input wire rd_en,
    input wire wr_en,
    input wire [63:0] globalCycle
);
    integer file;
    initial begin
        file = $fopen("output_request_stats.csv", "w");
        $fwrite(file, "RequestID,Read,Write,Cycle\n");
        $display("IN VERILOG OUTPUT");
    end

    reg [31:0] reqId = 0;

    always @(posedge clk) begin
        if (reset) begin
            reqId <= 0;
        end else if (resp_fire) begin
            $fwrite(file, "%d,%d,%d,%d\n", reqId, rd_en, wr_en, globalCycle);
            reqId <= reqId + 1;
        end
    end
endmodule
