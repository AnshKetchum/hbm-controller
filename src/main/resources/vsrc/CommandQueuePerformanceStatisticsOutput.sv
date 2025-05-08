module CommandQueuePerformanceStatisticsOutput(
    input wire clk,
    input wire reset,
    input wire resp_fire,
    input wire [31:0] addr,
    input wire [31:0] data,
    input wire [63:0] globalCycle,
    input wire [31:0] request_id
);
    integer file;
    initial begin
        file = $fopen("memory_response_queue_stats.csv", "w");
        $fwrite(file, "RequestID,Address,Type,Cycle\n");
    end


    always @(posedge clk) begin
        if (reset) begin
        end else if (resp_fire) begin
            $fwrite(file, "%d,%d,%d,%d\n", request_id, addr, data, globalCycle);
        end
    end
endmodule
