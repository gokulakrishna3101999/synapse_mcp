package com.synapsemcp.mcp;

import com.synapsemcp.common.TenantContext;
import com.synapsemcp.knowledgebase.CreateKnowledgeBaseRequest;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
import com.synapsemcp.knowledgebase.KnowledgeBaseService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * mcp_plan.md Stage 2 {@code create_knowledge_base} tool. {@code embedding_dim} is never a caller
 * input - always server-derived (rag_plan.md Stage 3, Grooming #5b), same as REST.
 */
@Component
public class CreateKnowledgeBaseMcpTool {

    private final KnowledgeBaseService knowledgeBaseService;

    CreateKnowledgeBaseMcpTool(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @McpTool(
            name = "create_knowledge_base",
            description =
                    "Creates a knowledge base for this tenant. Requires configure_model to have "
                            + "been called first.")
    public KnowledgeBaseResponse createKnowledgeBase(
            @McpToolParam(description = "Human-readable name for the new knowledge base")
                    String name) {
        return knowledgeBaseService.createKnowledgeBase(
                TenantContext.get(), new CreateKnowledgeBaseRequest(name));
    }
}
