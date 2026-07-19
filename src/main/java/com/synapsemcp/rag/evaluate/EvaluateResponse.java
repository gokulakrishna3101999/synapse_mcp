package com.synapsemcp.rag.evaluate;

import java.util.List;

/** rag_plan.md Stage 6c: "Relevance metrics: precision@K, recall@K, MRR (Mean Reciprocal Rank)". */
public record EvaluateResponse(
        List<EvaluateQueryResult> perQuery,
        double meanPrecisionAtK,
        double meanRecallAtK,
        double meanReciprocalRank) {
    public EvaluateResponse {
        perQuery = perQuery == null ? List.of() : List.copyOf(perQuery);
    }
}
