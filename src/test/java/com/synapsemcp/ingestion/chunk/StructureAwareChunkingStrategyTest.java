package com.synapsemcp.ingestion.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.ingestion.chunk.ChunkingStrategy.ChunkData;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructureAwareChunkingStrategyTest {

    private final StructureAwareChunkingStrategy strategy = new StructureAwareChunkingStrategy();

    @Test
    void supportsTextWithAnyHeadingLineRegardlessOfMimeType() {
        assertThat(strategy.supports("# Title\nbody", "text/plain")).isTrue();
        assertThat(strategy.supports("no headings here", "text/plain")).isFalse();
    }

    @Test
    void splitsAtEachHeadingAndTracksABreadcrumbHeadingPath() {
        String text =
                """
                # Title
                Intro paragraph.
                ## Sub Section
                Sub content.
                ### Deep Section
                Deep content.
                """;

        List<ChunkData> chunks = strategy.chunk(text, "text/markdown");

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).content()).isEqualTo("Intro paragraph.");
        assertThat(chunks.get(0).headingPath()).isEqualTo("Title");
        assertThat(chunks.get(1).content()).isEqualTo("Sub content.");
        assertThat(chunks.get(1).headingPath()).isEqualTo("Title > Sub Section");
        assertThat(chunks.get(2).content()).isEqualTo("Deep content.");
        assertThat(chunks.get(2).headingPath()).isEqualTo("Title > Sub Section > Deep Section");
        assertThat(chunks).extracting(ChunkData::strategy).containsOnly("structure-aware");
        assertThat(chunks).extracting(ChunkData::position).containsExactly(0, 1, 2);
    }

    @Test
    void clearsDeeperLevelsWhenAShallowerHeadingReappears() {
        String text =
                """
                # Title
                ## Section A
                content A
                ## Section B
                content B
                """;

        List<ChunkData> chunks = strategy.chunk(text, "text/markdown");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).headingPath()).isEqualTo("Title > Section A");
        assertThat(chunks.get(1).headingPath()).isEqualTo("Title > Section B");
    }

    @Test
    void neverFlushesAnEmptyChunkBetweenConsecutiveHeadingsWithNoBody() {
        String text =
                """
                # Title
                ## Empty Section
                ## Another Empty Section
                Only this has content.
                """;

        List<ChunkData> chunks = strategy.chunk(text, "text/markdown");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("Only this has content.");
        assertThat(chunks.get(0).headingPath()).isEqualTo("Title > Another Empty Section");
    }
}
