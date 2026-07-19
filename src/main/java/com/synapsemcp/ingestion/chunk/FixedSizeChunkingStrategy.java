package com.synapsemcp.ingestion.chunk;

import java.util.ArrayList;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * rag_plan.md Stage 5b: universal fallback, {@code LOWEST_PRECEDENCE} - always matches, only
 * reached when no more specific strategy claimed the document. Sliding window with overlap, sized
 * by {@link TokenEstimator}'s 3-tier table (converted token counts to characters via the same
 * chars-per-token ≈ 4 rule the estimator itself uses).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class FixedSizeChunkingStrategy implements ChunkingStrategy {

    @Override
    public boolean supports(String text, String mimeType) {
        return true;
    }

    @Override
    public List<ChunkData> chunk(String text, String mimeType) {
        int totalTokens = TokenEstimator.estimateTokens(text);
        int windowChars = TokenEstimator.windowCharsFor(totalTokens);
        int overlapChars = (int) (windowChars * TokenEstimator.overlapRatioFor(totalTokens));
        int step = Math.max(1, windowChars - overlapChars);

        List<ChunkData> chunks = new ArrayList<>();
        int position = 0;
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + windowChars, text.length());
            String chunkText = text.substring(start, end).strip();
            if (!chunkText.isEmpty()) {
                chunks.add(new ChunkData(chunkText, position++, null, "fixed-size"));
            }
            if (end >= text.length()) {
                break;
            }
        }
        return chunks;
    }
}
