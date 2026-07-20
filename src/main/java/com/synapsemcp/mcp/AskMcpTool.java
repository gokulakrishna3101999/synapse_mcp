package com.synapsemcp.mcp;

import com.synapsemcp.common.TenantContext;
import com.synapsemcp.rag.answer.AskRequest;
import com.synapsemcp.rag.answer.AskResponse;
import com.synapsemcp.rag.answer.ConversationTurn;
import com.synapsemcp.rag.answer.RagAnsweringService;
import com.synapsemcp.rag.retrieve.SearchMode;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * mcp_plan.md Stage 2 {@code ask} tool. Calls {@link RagAnsweringService#ask}, the non-streaming
 * {@code .call()}-backed method - an MCP tool call has no equivalent token-streaming response
 * shape, so {@code askStream} (rag_plan.md Stage 6b's SSE variant) is never used here. {@code
 * history} reuses {@link ConversationTurn} directly as the array element type - confirmed via a
 * schema-generator probe that {@code List<record>} parameters schema cleanly as an array of objects
 * (Grooming #19), unlike a lone top-level record parameter.
 */
@Component
public class AskMcpTool {

    private final RagAnsweringService ragAnsweringService;

    public AskMcpTool(RagAnsweringService ragAnsweringService) {
        this.ragAnsweringService = ragAnsweringService;
    }

    @McpTool(
            name = "ask",
            description =
                    "Answers a question using retrieval-augmented generation over a knowledge "
                            + "base's documents, returning the complete answer with citations in "
                            + "a single result.")
    public AskResponse ask(
            @McpToolParam(description = "Id of the knowledge base to answer from")
                    String knowledgeBaseId,
            @McpToolParam(description = "The question to answer") String question,
            @McpToolParam(
                            description = "Language to respond in (default: same as the question)",
                            required = false)
                    String language,
            @McpToolParam(
                            description =
                                    "Prior conversation turns (role: user or assistant, plus content)",
                            required = false)
                    List<ConversationTurn> history,
            @McpToolParam(
                            description =
                                    "Retrieval mode: hybrid, vector, or keyword (default hybrid)",
                            required = false)
                    String mode,
            @McpToolParam(
                            description =
                                    "Whether to rerank retrieved chunks with an LLM (default true)",
                            required = false)
                    Boolean rerank) {
        UUID id = McpToolInputs.parseUuid(knowledgeBaseId, "knowledgeBaseId");
        SearchMode searchMode = McpToolInputs.parseSearchMode(mode);
        AskRequest request = new AskRequest(question, language, history, searchMode, rerank);
        return ragAnsweringService.ask(TenantContext.get(), id, request);
    }
}
