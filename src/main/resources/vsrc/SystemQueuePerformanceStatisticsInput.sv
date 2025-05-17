module SystemQueuePerformanceStatisticsInput(
    input wire clk,
    input wire reset,
    input wire req_fire,
    input wire rd_en,
    input wire wr_en,
    input wire [31:0] addr,
    input wire [31:0] wdata,
    input wire [63:0] globalCycle,
    input wire [31:0] request_id
);
    integer file;
    initial begin
        file = $fopen("input_request_stats.csv", "w");
        $fwrite(file, "RequestID,Address,Read,Write,Cycle,Write Data\n");
        $display("IN VERILOG INPUT");
    end


    always @(posedge clk) begin
        if (reset) begin
        end else if (req_fire) begin
            $fwrite(file, "%d,%d,%d,%d,%d,%d\n", request_id, addr, rd_en, wr_en, globalCycle, wdata);
        end
    end
endmodule
