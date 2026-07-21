package com.synapsemcp.mcp;

import com.synapsemcp.common.TenantContext;
import com.synapsemcp.rag.retrieve.HybridRetrievalService;
import com.synapsemcp.rag.retrieve.SearchMode;
import com.synapsemcp.rag.retrieve.SearchRequest;
import com.synapsemcp.rag.retrieve.SearchResultChunk;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * mcp_plan.md Stage 2 {@code search} tool. Flat scalar parameters (Grooming #19) build the existing
 * {@link SearchRequest} internally - {@code rrfK} is left at its default, not exposed as a tool
 * param, matching the plan's own listed param set (knowledgebase, query, top_k, mode).
 */
@Component
public class SearchMcpTool {

    private final HybridRetrievalService hybridRetrievalService;
    private final KnowledgeBaseNameResolver knowledgeBaseNameResolver;

    public SearchMcpTool(
            HybridRetrievalService hybridRetrievalService,
            KnowledgeBaseNameResolver knowledgeBaseNameResolver) {
        this.hybridRetrievalService = hybridRetrievalService;
        this.knowledgeBaseNameResolver = knowledgeBaseNameResolver;
    }

    @McpTool(
            name = "search",
            description = "Hybrid retrieval over a knowledge base's indexed document chunks.")
    public List<SearchResultChunk> search(
            @McpToolParam(
                            description =
                                    "Name of the knowledge base to search - if omitted, uses this"
                                            + " account's active knowledge base (see"
                                            + " switch_knowledge_base)",
                            required = false)
                    String knowledgeBaseName,
            @McpToolParam(description = "Search query text") String query,
            @McpToolParam(
                            description = "Maximum number of results to return (default 10)",
                            required = false)
                    Integer topK,
            @McpToolParam(
                            description =
                                    "Retrieval mode: hybrid, vector, or keyword (default hybrid)",
                            required = false)
                    String mode,
            @McpToolParam(
                            description = "Whether to rerank results with an LLM (default true)",
                            required = false)
                    Boolean rerank) {
        UUID id = knowledgeBaseNameResolver.resolve(knowledgeBaseName);
        SearchMode searchMode = McpToolInputs.parseSearchMode(mode);
        SearchRequest request = new SearchRequest(query, topK, null, searchMode, rerank);
        return hybridRetrievalService.search(TenantContext.get(), id, request);
    }
}
