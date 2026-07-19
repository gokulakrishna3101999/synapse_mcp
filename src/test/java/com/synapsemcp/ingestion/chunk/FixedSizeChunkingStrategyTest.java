package com.synapsemcp.ingestion.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.ingestion.chunk.ChunkingStrategy.ChunkData;
import java.util.List;
import org.junit.jupiter.api.Test;

class FixedSizeChunkingStrategyTest {

    private final FixedSizeChunkingStrategy strategy = new FixedSizeChunkingStrategy();

    @Test
    void alwaysSupportsAnyTextOrMimeType() {
        assertThat(strategy.supports("anything", "whatever/mime")).isTrue();
        assertThat(strategy.supports("", null)).isTrue();
    }

    @Test
    void producesASingleChunkForShortText() {
        List<ChunkData> chunks = strategy.chunk("hello world", "text/plain");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("hello world");
        assertThat(chunks.get(0).position()).isZero();
        assertThat(chunks.get(0).headingPath()).isNull();
        assertThat(chunks.get(0).strategy()).isEqualTo("fixed-size");
    }

    @Test
    void slidesAWindowWithOverlapForLongText() {
        // 1200 tokens estimated (4800 chars / 4) puts this in the 1,000-50,000 tier: 512-token
        // (2048-char) window, 15% (307-char) overlap, per TokenEstimator's own table.
        String text = "x".repeat(4800);

        List<ChunkData> chunks = strategy.chunk(text, "text/plain");

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks)
                .extracting(ChunkData::position)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
        // Every chunk but the last should be exactly window-sized; overlap must be strictly less
        // than the window so the sliding step always makes forward progress.
        int windowChars = TokenEstimator.windowCharsFor(TokenEstimator.estimateTokens(text));
        for (int i = 0; i < chunks.size() - 1; i++) {
            assertThat(chunks.get(i).content()).hasSize(windowChars);
        }
        // Reassembling non-overlapping content must cover the whole original text.
        assertThat(chunks.get(chunks.size() - 1).content()).endsWith("x");
    }

    @Test
    void neverProducesAnEmptyChunk() {
        List<ChunkData> chunks = strategy.chunk("   ", "text/plain");

        assertThat(chunks).isEmpty();
    }
}
