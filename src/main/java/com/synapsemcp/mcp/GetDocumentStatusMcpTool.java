package com.synapsemcp.mcp;

import com.synapsemcp.common.TenantContext;
import com.synapsemcp.ingestion.IngestionJobService;
import com.synapsemcp.ingestion.JobStatusResponse;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** mcp_plan.md Stage 2 {@code get_document_status} tool - looked up by document id, not job id. */
@Component
public class GetDocumentStatusMcpTool {

    private final IngestionJobService ingestionJobService;

    public GetDocumentStatusMcpTool(IngestionJobService ingestionJobService) {
        this.ingestionJobService = ingestionJobService;
    }

    @McpTool(
            name = "get_document_status",
            description =
                    "Returns a document's upload/indexing status by document id: PENDING, "
                            + "INDEXING, READY, or FAILED (with error detail if failed).")
    public JobStatusResponse getDocumentStatus(
            @McpToolParam(description = "Document id") String documentId) {
        UUID id = McpToolInputs.parseUuid(documentId, "documentId");
        return ingestionJobService.getStatusByDocumentId(TenantContext.get(), id);
    }
}
