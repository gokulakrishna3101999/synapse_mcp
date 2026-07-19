package com.synapsemcp.knowledgebase;

import java.util.UUID;

/**
 * {@code knowledge_bases} has no {@code created_at} column, unlike every other table in this schema
 * (rag_plan.md Stage 0.5 Table Definitions) - matched here rather than introduced as an unrequested
 * schema change.
 */
public record KnowledgeBaseResponse(UUID id, String name, int embeddingDim) {}
