package com.synapsemcp.tenant;

import java.time.Instant;
import java.util.UUID;

/**
 * Deliberately has no credential fields - never echoed back in any response (rag_plan.md Stage 2).
 */
public record ModelConfigResponse(
        UUID tenantId,
        String chatProvider,
        String chatModel,
        String embeddingProvider,
        String embeddingModel,
        Instant createdAt) {}
