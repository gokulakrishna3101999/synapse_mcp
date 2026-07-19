package com.synapsemcp.rag.retrieve;

import java.util.UUID;

/**
 * rag_plan.md Stage 6a: a chunk id ranked by one retrieval method (vector or keyword), highest
 * {@code score} first. Shared shape both {@code VectorSearchService} and {@code
 * LuceneIndexManager}'s search method return, so {@code ReciprocalRankFusion} can merge either
 * source's ranked list without caring which retrieval method produced it.
 */
public record ScoredChunkId(UUID chunkId, double score) {}
