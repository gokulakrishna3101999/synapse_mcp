package com.synapsemcp.knowledgebase;

import com.synapsemcp.document.DocumentStatusSummary;
import java.util.UUID;

/**
 * {@code knowledge_bases} has no {@code created_at} column, unlike every other table in this schema
 * (rag_plan.md Stage 0.5 Table Definitions) - matched here rather than introduced as an unrequested
 * schema change.
 *
 * <p>{@code documentStatusSummary} added mcp_plan.md Stage 2 for the {@code list_knowledge_bases}/
 * {@code get_tenant} MCP tools - an additive field on the existing shared response, so the REST
 * {@code GET /api/v1/knowledgebase} endpoint gains the same rollup for free (Grooming #19).
 */
public record KnowledgeBaseResponse(
        UUID id, String name, int embeddingDim, DocumentStatusSummary documentStatusSummary) {}
