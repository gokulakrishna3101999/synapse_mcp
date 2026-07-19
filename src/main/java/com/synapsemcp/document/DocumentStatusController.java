package com.synapsemcp.document;

import com.synapsemcp.common.TenantContext;
import com.synapsemcp.ingestion.IngestionJobService;
import com.synapsemcp.ingestion.JobStatusResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * rag_plan.md Stage 4/5 REST API Summary: {@code GET /api/v1/documents/{documentId}/status} - "Get
 * the status of a specific document (maps to job status)." Found missing during a completeness
 * audit (`plan.md` §9 2026-07-18) - every other endpoint in the plan's own "complete list of REST
 * endpoints" was implemented except this one; {@code GET /api/v1/jobs/{jobId}} alone left no way to
 * poll status from a bare {@code documentId} (e.g. the id returned by the upload endpoint) without
 * also separately tracking its job id.
 */
@RestController
@RequestMapping("/api/v1/documents/{documentId}/status")
public class DocumentStatusController {

    private final IngestionJobService ingestionJobService;

    public DocumentStatusController(IngestionJobService ingestionJobService) {
        this.ingestionJobService = ingestionJobService;
    }

    @GetMapping
    public JobStatusResponse getStatus(@PathVariable UUID documentId) {
        return ingestionJobService.getStatusByDocumentId(TenantContext.get(), documentId);
    }
}
