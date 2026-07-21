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
    private final KnowledgeBaseNameResolver knowledgeBaseNameResolver;

    UpdateKnowledgeBaseMcpTool(
            KnowledgeBaseService knowledgeBaseService,
            KnowledgeBaseNameResolver knowledgeBaseNameResolver) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeBaseNameResolver = knowledgeBaseNameResolver;
    }

    /**
     * Deliberately named {@code knowledgeBaseName} (the knowledge base to rename) and {@code
     * newName} (what to rename it to), not two parameters that could both plausibly be called
     * "name" - user-requested (2026-07-22) name-based access made this specific tool the one place
     * where that ambiguity would otherwise be genuinely confusing.
     */
    @McpTool(name = "update_knowledge_base", description = "Renames an existing knowledge base.")
    public KnowledgeBaseResponse updateKnowledgeBase(
            @McpToolParam(
                            description =
                                    "Name of the knowledge base to rename - if omitted, uses this"
                                            + " account's active knowledge base (see"
                                            + " switch_knowledge_base)",
                            required = false)
                    String knowledgeBaseName,
            @McpToolParam(description = "New name for the knowledge base") String newName) {
        UUID id = knowledgeBaseNameResolver.resolve(knowledgeBaseName);
        return knowledgeBaseService.updateKnowledgeBase(
                TenantContext.get(), id, new UpdateKnowledgeBaseRequest(newName));
    }
}
