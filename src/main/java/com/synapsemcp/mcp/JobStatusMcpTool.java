package com.synapsemcp.mcp;

import com.synapsemcp.common.TenantContext;
import com.synapsemcp.ingestion.IngestionJobService;
import com.synapsemcp.ingestion.JobStatusResponse;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** mcp_plan.md Stage 2 {@code job_status} tool - companion to {@code ingest}. */
@Component
public class JobStatusMcpTool {

    private final IngestionJobService ingestionJobService;

    public JobStatusMcpTool(IngestionJobService ingestionJobService) {
        this.ingestionJobService = ingestionJobService;
    }

    @McpTool(
            name = "job_status",
            description =
                    "Polls the status of an ingestion job started by the ingest tool: PENDING, "
                            + "INDEXING, READY, or FAILED (with error detail if failed).")
    public JobStatusResponse jobStatus(
            @McpToolParam(description = "Job id returned by the ingest tool") String jobId) {
        UUID id = McpToolInputs.parseUuid(jobId, "jobId");
        return ingestionJobService.getJobStatus(TenantContext.get(), id);
    }
}
