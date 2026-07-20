package com.synapsemcp.mcp;

import com.synapsemcp.common.TenantContext;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
import com.synapsemcp.knowledgebase.KnowledgeBaseService;
import com.synapsemcp.knowledgebase.UpdateKnowledgeBaseRequest;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** mcp_plan.md Stage 2 {@code update_knowledge_base} tool - renames an existing knowledge base. */
@Component
public class UpdateKnowledgeBaseMcpTool {

    private final KnowledgeBaseService knowledgeBaseService;

    UpdateKnowledgeBaseMcpTool(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @McpTool(name = "update_knowledge_base", description = "Renames an existing knowledge base.")
    public KnowledgeBaseResponse updateKnowledgeBase(
            @McpToolParam(description = "Id of the knowledge base to rename")
                    String knowledgeBaseId,
            @McpToolParam(description = "New name for the knowledge base") String name) {
        UUID id = McpToolInputs.parseUuid(knowledgeBaseId, "knowledgeBaseId");
        return knowledgeBaseService.updateKnowledgeBase(
                TenantContext.get(), id, new UpdateKnowledgeBaseRequest(name));
    }
}
