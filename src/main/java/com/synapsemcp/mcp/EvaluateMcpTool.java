package com.synapsemcp.mcp;

import com.synapsemcp.common.TenantContext;
import com.synapsemcp.rag.evaluate.EvaluateQuery;
import com.synapsemcp.rag.evaluate.EvaluateRequest;
import com.synapsemcp.rag.evaluate.EvaluateResponse;
import com.synapsemcp.rag.evaluate.EvaluationService;
import com.synapsemcp.rag.retrieve.SearchMode;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * mcp_plan.md Stage 2 {@code evaluate} tool - runs the golden-query harness against a knowledge
 * base and returns per-query and mean relevance metrics. {@code queries} reuses {@link
 * EvaluateQuery} directly as the array element type, same reasoning as {@code ask}'s {@code
 * history} (Grooming #19).
 */
@Component
public class EvaluateMcpTool {

    private final EvaluationService evaluationService;

    public EvaluateMcpTool(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @McpTool(
            name = "evaluate",
            description =
                    "Runs a set of golden queries (each with expected chunk ids) against a "
                            + "knowledge base and returns precision/recall/reciprocal-rank metrics "
                            + "per query and averaged.")
    public EvaluateResponse evaluate(
            @McpToolParam(description = "Id of the knowledge base to evaluate")
                    String knowledgeBaseId,
            @McpToolParam(
                            description =
                                    "Golden queries, each with a query string and its list of "
                                            + "expected chunk ids")
                    List<EvaluateQuery> queries,
            @McpToolParam(
                            description = "Results to retrieve per query (default 10)",
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
        UUID id = McpToolInputs.parseUuid(knowledgeBaseId, "knowledgeBaseId");
        SearchMode searchMode = McpToolInputs.parseSearchMode(mode);
        EvaluateRequest request = new EvaluateRequest(queries, topK, searchMode, rerank);
        return evaluationService.evaluate(TenantContext.get(), id, request);
    }
}
