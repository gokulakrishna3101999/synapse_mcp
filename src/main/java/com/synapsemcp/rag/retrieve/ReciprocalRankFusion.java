package com.synapsemcp.rag.retrieve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * rag_plan.md Stage 6a: {@code score(d) = sum 1/(k + rank_i(d))} across every ranked list a chunk
 * id appears in, {@code rank_i} is 1-based. Only rank position matters, not each source's own raw
 * score (vector cosine similarity and BM25 are on incomparable scales, which is the entire reason
 * RRF exists) - the {@link ScoredChunkId#score()} of each input list is ignored here; only its
 * order is read. Dedupes by chunk id via {@link LinkedHashMap} accumulation, then sorts descending
 * by the fused score.
 */
public final class ReciprocalRankFusion {

    public static final int DEFAULT_K = 60;

    private ReciprocalRankFusion() {}

    public static List<ScoredChunkId> merge(List<List<ScoredChunkId>> rankedLists, int k) {
        Map<UUID, Double> fusedScores = new LinkedHashMap<>();
        for (List<ScoredChunkId> rankedList : rankedLists) {
            for (int i = 0; i < rankedList.size(); i++) {
                UUID chunkId = rankedList.get(i).chunkId();
                int rank = i + 1;
                fusedScores.merge(chunkId, 1.0 / (k + rank), Double::sum);
            }
        }

        List<ScoredChunkId> merged = new ArrayList<>(fusedScores.size());
        for (Map.Entry<UUID, Double> entry : fusedScores.entrySet()) {
            merged.add(new ScoredChunkId(entry.getKey(), entry.getValue()));
        }
        merged.sort(Comparator.comparingDouble(ScoredChunkId::score).reversed());
        return merged;
    }
}
