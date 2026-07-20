package com.synapsemcp.tenant;

import com.synapsemcp.document.DocumentStatusSummary;
import java.time.Instant;
import java.util.UUID;

/**
 * mcp_plan.md Stage 2 `get_tenant` tool - no REST equivalent exists (there is no {@code GET
 * /api/v1/tenants/{id}} endpoint). {@code documentStatusSummary} is the sum of every one of the
 * tenant's knowledge_bases' own summaries, not a per-KB breakdown - see {@code
 * KnowledgeBaseResponse.documentStatusSummary} for that.
 */
public record TenantDetailResponse(
        UUID tenantId,
        String name,
        Instant createdAt,
        int knowledgeBaseCount,
        DocumentStatusSummary documentStatusSummary) {}
