package com.synapsemcp.knowledgebase;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code embedding_dim} is deliberately not a field here - it is always server-derived via a live
 * probe of the tenant's configured embedding model, never a caller input (rag_plan.md Stage 3,
 * Grooming #5b).
 */
public record CreateKnowledgeBaseRequest(@NotBlank String name) {}
