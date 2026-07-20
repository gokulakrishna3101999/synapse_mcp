package com.synapsemcp.mcp;

import com.synapsemcp.common.TenantContext;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
import com.synapsemcp.knowledgebase.KnowledgeBaseService;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/**
 * mcp_plan.md Stage 2 {@code list_knowledge_bases} tool - name, doc count, and status summary per
 * knowledge base, via the shared {@link KnowledgeBaseResponse#documentStatusSummary()} rollup
 * (Grooming #19).
 */
@Component
public class ListKnowledgeBasesMcpTool {

    private final KnowledgeBaseService knowledgeBaseService;

    ListKnowledgeBasesMcpTool(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @McpTool(
            name = "list_knowledge_bases",
            description =
                    "Lists this tenant's knowledge bases, each with its name, embedding "
                            + "dimension, and a document-count/status-summary rollup.")
    public List<KnowledgeBaseResponse> listKnowledgeBases() {
        return knowledgeBaseService.listKnowledgeBases(TenantContext.get());
    }
}
