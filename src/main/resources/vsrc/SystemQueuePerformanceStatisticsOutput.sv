module SystemQueuePerformanceStatisticsOutput(
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
    initial begin
        file = $fopen("output_request_stats.csv", "w");
        $fwrite(file, "RequestID,Address,Read,Write,Cycle\n");
        $display("IN VERILOG OUTPUT");
    end


    always @(posedge clk) begin
        if (reset) begin
        end else if (resp_fire) begin
            $fwrite(file, "%d,%d,%d,%d,%d\n", request_id, addr,rd_en, wr_en, globalCycle);
        end
    end
endmodule
