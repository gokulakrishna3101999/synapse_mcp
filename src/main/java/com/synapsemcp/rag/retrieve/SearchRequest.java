package com.synapsemcp.rag.retrieve;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * rag_plan.md Stage 6a: {@code topK}/{@code rrfK}/{@code mode}/{@code rerank} all have
 * plan-specified defaults (10, {@link ReciprocalRankFusion#DEFAULT_K}, hybrid, enabled) - applied
 * in the compact constructor so a caller can omit any of them and still get the documented default
 * rather than a validation error.
 */
public record SearchRequest(
        @NotBlank String query,
        @Min(1) @Max(100) Integer topK,
        @Min(1) Integer rrfK,
        SearchMode mode,
        Boolean rerank) {

    public SearchRequest {
        if (topK == null) {
            topK = 10;
        }
        if (rrfK == null) {
            rrfK = ReciprocalRankFusion.DEFAULT_K;
        }
        if (mode == null) {
            mode = SearchMode.HYBRID;
        }
        if (rerank == null) {
            // rag_plan.md Stage 6a+: "Reranking is optional and can be disabled via a request
            // parameter (default: enabled in hybrid mode)."
            rerank = true;
        }
    }
}
