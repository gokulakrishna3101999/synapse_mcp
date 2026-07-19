package com.synapsemcp.rag.retrieve;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** rag_plan.md Stage 6a: one ranked result - the plan's own "Result shape" row, verbatim. */
public record SearchResultChunk(
        UUID chunkId,
        UUID documentId,
        String filename,
        String content,
        double score,
        Map<String, Object> metadata) {
    public SearchResultChunk {
        metadata = metadata == null ? Map.of() : new HashMap<>(metadata);
    }

    /**
     * Overrides the record's auto-generated accessor - {@code List.copyOf}-backed fields elsewhere
     * in this codebase's DTOs are recognized as immutable by SpotBugs' EI_EXPOSE_REP check and
     * don't need this, but this field is a mutable {@code HashMap} copy (chosen over {@code
     * Map.copyOf} because that rejects null values, which JSONB-sourced metadata could plausibly
     * contain) - the default accessor would still return that same mutable instance directly on
     * every call.
     */
    @Override
    public Map<String, Object> metadata() {
        return new HashMap<>(metadata);
    }
}
