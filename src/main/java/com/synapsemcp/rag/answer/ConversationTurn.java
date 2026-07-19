package com.synapsemcp.rag.answer;

import jakarta.validation.constraints.NotBlank;

/**
 * rag_plan.md Stage 6b: one turn of the caller-supplied conversation history - "Optional
 * caller-supplied conversation history (stateless API)". An invalid {@code role} throws {@link
 * IllegalArgumentException} from this compact constructor, which Jackson wraps as {@code
 * HttpMessageNotReadableException} - already mapped to {@code 400} (same established pattern as
 * {@link com.synapsemcp.rag.retrieve.SearchMode#fromValue}), so no new exception handling is
 * needed.
 */
public record ConversationTurn(@NotBlank String role, @NotBlank String content) {

    public ConversationTurn {
        if (role != null && !"user".equalsIgnoreCase(role) && !"assistant".equalsIgnoreCase(role)) {
            throw new IllegalArgumentException(
                    "role must be \"user\" or \"assistant\", got: " + role);
        }
    }

    public boolean isAssistant() {
        return "assistant".equalsIgnoreCase(role);
    }
}
