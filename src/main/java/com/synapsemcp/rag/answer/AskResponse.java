package com.synapsemcp.rag.answer;

import java.util.List;

public record AskResponse(String answer, List<Citation> citations) {
    public AskResponse {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
