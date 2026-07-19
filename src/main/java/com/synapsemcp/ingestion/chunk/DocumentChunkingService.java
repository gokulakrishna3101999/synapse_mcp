package com.synapsemcp.ingestion.chunk;

import com.synapsemcp.ingestion.chunk.ChunkingStrategy.ChunkData;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * rag_plan.md Stage 5b: single-chunk shortcut, then dispatch to the first supporting {@link
 * ChunkingStrategy} in Spring's injected (i.e. {@code @Order}-controlled) bean order.
 */
@Service
public class DocumentChunkingService {

    private final List<ChunkingStrategy> strategies;

    DocumentChunkingService(List<ChunkingStrategy> strategies) {
        this.strategies = strategies;
    }

    /**
     * Returns one chunk, unchunked, if the whole document is already smaller than a single window
     * (with 20% headroom) would hold - regardless of format, before any strategy is even consulted.
     */
    public List<ChunkData> chunk(String text, String mimeType) {
        int totalTokens = TokenEstimator.estimateTokens(text);
        int windowTokens = TokenEstimator.windowTokensFor(totalTokens);
        if (totalTokens < windowTokens * 1.2) {
            return List.of(new ChunkData(text.strip(), 0, null, "single-chunk"));
        }

        for (ChunkingStrategy strategy : strategies) {
            if (strategy.supports(text, mimeType)) {
                return strategy.chunk(text, mimeType);
            }
        }
        throw new IllegalStateException(
                "no ChunkingStrategy matched - FixedSizeChunkingStrategy should always match");
    }
}
