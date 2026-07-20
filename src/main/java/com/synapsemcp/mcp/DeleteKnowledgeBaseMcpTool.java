package com.synapsemcp.mcp;

import com.synapsemcp.common.TenantContext;
import com.synapsemcp.knowledgebase.KnowledgeBaseService;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * mcp_plan.md Stage 2 {@code delete_knowledge_base} tool - permanently deletes a knowledge base and
 * all its documents/chunks (cascades at the DB level, same as REST).
 */
@Component
public class DeleteKnowledgeBaseMcpTool {

    private final KnowledgeBaseService knowledgeBaseService;

    DeleteKnowledgeBaseMcpTool(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @McpTool(
            name = "delete_knowledge_base",
            description =
                    "Permanently deletes a knowledge base and all its associated documents and "
                            + "chunks. This cannot be undone.")
    public DeleteKnowledgeBaseResult deleteKnowledgeBase(
            @McpToolParam(description = "Id of the knowledge base to delete")
                    String knowledgeBaseId) {
        UUID id = McpToolInputs.parseUuid(knowledgeBaseId, "knowledgeBaseId");
        knowledgeBaseService.deleteKnowledgeBase(TenantContext.get(), id);
        return new DeleteKnowledgeBaseResult(id, true);
    }
}
