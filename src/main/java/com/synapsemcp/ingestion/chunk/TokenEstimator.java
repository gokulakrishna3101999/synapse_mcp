package com.synapsemcp.ingestion.chunk;

/**
 * rag_plan.md Stage 5b: {@code ceil(text.length / 4.0)} - the chars-per-token ≈ 4 rule of thumb,
 * deliberately approximate since no provider/tokenizer is fixed at chunk-design time (chunking
 * happens before the knowledge_base's actual embedding model is consulted for anything beyond its
 * dimension).
 *
 * <p>{@link #windowTokensFor}/{@link #overlapRatioFor} are the same 3-tier table {@code
 * FixedSizeChunkingStrategy} uses for its own chunk sizing, shared here so {@code
 * DocumentChunkingService}'s single-chunk shortcut ("is this document small enough that even one
 * chunk at the size FixedSizeChunkingStrategy would normally use already fits the whole text?")
 * stays consistent with the fallback strategy's actual behavior - user-confirmed interpretation of
 * rag_plan.md's `windowTokensFor(totalTokens)` reference, which the plan text never defines
 * elsewhere (`plan.md` §9, 2026-07-17).
 */
public final class TokenEstimator {

    private static final int CHARS_PER_TOKEN = 4;

    private TokenEstimator() {}

    public static int estimateTokens(String text) {
        return (int) Math.ceil(text.length() / (double) CHARS_PER_TOKEN);
    }

    public static int windowTokensFor(int totalTokens) {
        if (totalTokens < 1_000) {
            return 256;
        }
        if (totalTokens <= 50_000) {
            return 512;
        }
        return 1_024;
    }

    public static double overlapRatioFor(int totalTokens) {
        if (totalTokens < 1_000) {
            return 0.10;
        }
        if (totalTokens <= 50_000) {
            return 0.15;
        }
        return 0.20;
    }

    public static int windowCharsFor(int totalTokens) {
        return windowTokensFor(totalTokens) * CHARS_PER_TOKEN;
    }
}
