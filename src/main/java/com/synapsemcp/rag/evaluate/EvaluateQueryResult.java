package com.synapsemcp.rag.evaluate;

/**
 * rag_plan.md Stage 6c: per-query relevance metrics. {@code precisionAtK}/{@code recallAtK} use
 * {@code min(K, retrieved.size())} as the denominator/starting point per standard IR convention
 * when fewer than {@code K} results come back; {@code reciprocalRank} is {@code 1/rank} of the
 * first relevant hit (0 if none found) - the mean of this field across a batch, not this field
 * itself, is what "MRR" conventionally refers to (see {@link EvaluateResponse#meanReciprocalRank}).
 */
public record EvaluateQueryResult(
        String query, double precisionAtK, double recallAtK, double reciprocalRank) {}
