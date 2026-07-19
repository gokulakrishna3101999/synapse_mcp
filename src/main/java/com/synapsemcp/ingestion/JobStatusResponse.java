package com.synapsemcp.ingestion;

import com.synapsemcp.common.IngestionStatus;
import java.time.Instant;
import java.util.UUID;

/** rag_plan.md Stage 5: {@code GET /api/v1/jobs/{jobId}} tenant-scoped status polling response. */
public record JobStatusResponse(
        UUID jobId,
        UUID documentId,
        IngestionStatus status,
        String stage,
        String errorDetail,
        Instant createdAt,
        Instant updatedAt) {}
