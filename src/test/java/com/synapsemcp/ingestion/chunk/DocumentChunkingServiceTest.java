package com.synapsemcp.ingestion.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.ingestion.chunk.ChunkingStrategy.ChunkData;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentChunkingServiceTest {

    @Test
    void returnsASingleUnchunkedChunkWhenTextFitsInOneWindowWithHeadroom() {
        // Real strategies would also match "hello world", but the shortcut must win before any
        // strategy is even consulted - passing no strategies at all proves that.
        DocumentChunkingService service = new DocumentChunkingService(List.of());

        List<ChunkData> chunks = service.chunk("hello world", "text/plain");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("hello world");
        assertThat(chunks.get(0).strategy()).isEqualTo("single-chunk");
        assertThat(chunks.get(0).headingPath()).isNull();
    }

    @Test
    void dispatchesToTheFirstStrategyThatSupportsTheTextWhenTooLargeForTheShortcut() {
        String longText = "x".repeat(10_000);
        ChunkingStrategy first = mock(ChunkingStrategy.class);
        ChunkingStrategy second = mock(ChunkingStrategy.class);
        when(first.supports(longText, "text/plain")).thenReturn(false);
        when(second.supports(longText, "text/plain")).thenReturn(true);
        when(second.chunk(longText, "text/plain"))
                .thenReturn(List.of(new ChunkData("chunked", 0, null, "stub")));
        DocumentChunkingService service = new DocumentChunkingService(List.of(first, second));

        List<ChunkData> chunks = service.chunk(longText, "text/plain");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).strategy()).isEqualTo("stub");
    }

    @Test
    void throwsWhenNoStrategySupportsTextTooLargeForTheShortcut() {
        String longText = "x".repeat(10_000);
        DocumentChunkingService service = new DocumentChunkingService(List.of());

        assertThatThrownBy(() -> service.chunk(longText, "text/plain"))
                .isInstanceOf(IllegalStateException.class);
    }
}
