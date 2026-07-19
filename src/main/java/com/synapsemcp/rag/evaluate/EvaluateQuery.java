package com.synapsemcp.rag.evaluate;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;

/** rag_plan.md Stage 6c: one golden query with its expected relevant chunk ids. */
public record EvaluateQuery(@NotBlank String query, List<UUID> expectedChunkIds) {

    public EvaluateQuery {
        expectedChunkIds = expectedChunkIds == null ? List.of() : List.copyOf(expectedChunkIds);
    }
}
