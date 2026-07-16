package com.synapsemcp.tenant;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code chatApiKey}/{@code embeddingApiKey} are optional - e.g. a tenant using only Ollama
 * typically needs no API key at all (rag_plan.md Stage 2). Model names are never validated at write
 * time (Grooming #4) - only providers are, against the same allow-list the DB `CHECK` constraint
 * enforces.
 */
public record ConfigureModelRequest(
        @NotBlank String chatProvider,
        @NotBlank String chatModel,
        @NotBlank String embeddingProvider,
        @NotBlank String embeddingModel,
        String chatApiKey,
        String embeddingApiKey) {}
