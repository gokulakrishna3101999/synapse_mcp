package com.synapsemcp.knowledgebase;

import jakarta.validation.constraints.NotBlank;

/** Name only - {@code embedding_dim} is immutable once set (rag_plan.md Stage 3, Grooming #5a). */
public record UpdateKnowledgeBaseRequest(@NotBlank String name) {}
