package com.synapsemcp.rag.retrieve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {

    @Test
    void mergeRanksAChunkAppearingInBothListsAboveOneAppearingInOnlyOne() {
        UUID inBoth = UUID.randomUUID();
        UUID vectorOnly = UUID.randomUUID();
        UUID keywordOnly = UUID.randomUUID();
        List<ScoredChunkId> vectorRanked = List.of(scored(inBoth, 0.9), scored(vectorOnly, 0.5));
        List<ScoredChunkId> keywordRanked = List.of(scored(inBoth, 12.0), scored(keywordOnly, 4.0));

        List<ScoredChunkId> merged =
                ReciprocalRankFusion.merge(
                        List.of(vectorRanked, keywordRanked), ReciprocalRankFusion.DEFAULT_K);

        assertThat(merged.get(0).chunkId()).isEqualTo(inBoth);
        assertThat(merged).extracting(ScoredChunkId::chunkId).contains(vectorOnly, keywordOnly);
    }

    @Test
    void mergeComputesTheDocumentedRrfFormula() {
        UUID rankOneInBoth = UUID.randomUUID();
        // rank 1 in both lists (1-based): score = 1/(60+1) + 1/(60+1) = 2/61
        List<ScoredChunkId> listA = List.of(scored(rankOneInBoth, 1.0));
        List<ScoredChunkId> listB = List.of(scored(rankOneInBoth, 1.0));

        List<ScoredChunkId> merged = ReciprocalRankFusion.merge(List.of(listA, listB), 60);

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).score()).isCloseTo(2.0 / 61, within(1e-9));
    }

    @Test
    void mergeDedupesByChunkIdAcrossLists() {
        UUID chunkId = UUID.randomUUID();
        List<ScoredChunkId> listA = List.of(scored(chunkId, 1.0));
        List<ScoredChunkId> listB = List.of(scored(chunkId, 1.0));

        List<ScoredChunkId> merged = ReciprocalRankFusion.merge(List.of(listA, listB), 60);

        assertThat(merged).hasSize(1);
    }

    @Test
    void mergeHandlesAnEmptyList() {
        assertThat(ReciprocalRankFusion.merge(List.of(List.of(), List.of()), 60)).isEmpty();
    }

    @Test
    void mergeIgnoresEachInputListsOwnRawScoreAndUsesRankPositionOnly() {
        UUID higherRawScoreButRankedSecond = UUID.randomUUID();
        UUID lowerRawScoreButRankedFirst = UUID.randomUUID();
        // Deliberately out of raw-score order - RRF must read rank position, not the field value.
        List<ScoredChunkId> ranked =
                List.of(
                        scored(lowerRawScoreButRankedFirst, 0.1),
                        scored(higherRawScoreButRankedSecond, 99.0));

        List<ScoredChunkId> merged = ReciprocalRankFusion.merge(List.of(ranked), 60);

        assertThat(merged.get(0).chunkId()).isEqualTo(lowerRawScoreButRankedFirst);
    }

    private static ScoredChunkId scored(UUID chunkId, double score) {
        return new ScoredChunkId(chunkId, score);
    }
}
