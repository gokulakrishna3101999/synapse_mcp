package com.synapsemcp.rag.evaluate;

import com.synapsemcp.rag.retrieve.SearchMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * rag_plan.md Stage 6c: {@code topK}/{@code mode}/{@code rerank} run each golden query through the
 * same retrieval pipeline as {@code /search} (rag_plan.md: "Runs each query through the retrieval
 * pipeline (same as /search)") - defaults mirror {@code SearchRequest}'s own (10, hybrid, enabled).
 */
public record EvaluateRequest(
        @NotEmpty List<@Valid EvaluateQuery> queries,
        Integer topK,
        SearchMode mode,
        Boolean rerank) {

    public EvaluateRequest {
        // Null-safe (not just List.copyOf(queries), which would NPE before @NotEmpty's own
        // validation - that runs on the already-constructed record - ever got a chance to reject
        // a genuinely missing field with a proper 400 instead of a raw 500).
        queries = queries == null ? List.of() : List.copyOf(queries);
        if (topK == null) {
            topK = 10;
        }
        if (mode == null) {
            mode = SearchMode.HYBRID;
        }
        if (rerank == null) {
            rerank = true;
        }
    }
}
