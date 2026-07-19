package com.synapsemcp.rag.answer;

import com.synapsemcp.rag.retrieve.SearchMode;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * rag_plan.md Stage 6b: {@code history}/{@code mode}/{@code rerank} all have documented defaults
 * (none, hybrid, enabled) - applied in the compact constructor so a caller can omit any of them.
 * {@code language} is deliberately left {@code null}-able with no default substituted here - a
 * missing value means "auto-detect from the question" (delegated to the chat model itself in its
 * system prompt, see {@link RagAnsweringService} - no separate Java-side language-detection
 * dependency was added for this, a deliberate scope decision), not a fixed fallback language.
 */
public record AskRequest(
        @NotBlank String question,
        String language,
        List<ConversationTurn> history,
        SearchMode mode,
        Boolean rerank) {

    public AskRequest {
        history = history == null ? List.of() : List.copyOf(history);
        if (mode == null) {
            mode = SearchMode.HYBRID;
        }
        if (rerank == null) {
            rerank = true;
        }
    }
}
