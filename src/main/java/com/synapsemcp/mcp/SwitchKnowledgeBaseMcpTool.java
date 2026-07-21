package com.synapsemcp.mcp;

import com.synapsemcp.common.TenantContext;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * User-requested (2026-07-22) {@code switch_knowledge_base} tool - see {@link
 * McpActiveKnowledgeBaseSwitchingService}'s Javadoc for the full design rationale. Gated normally
 * by {@link McpToolAccessAspect} (not on its unlinked-account allow-list, unlike {@code
 * create_tenant}/ {@code switch_tenant}): switching a knowledge base is meaningless without an
 * already-linked tenant, so requiring one first is correct, not an oversight.
 */
@Component
public class SwitchKnowledgeBaseMcpTool {

    private final McpActiveKnowledgeBaseSwitchingService switchingService;

    SwitchKnowledgeBaseMcpTool(McpActiveKnowledgeBaseSwitchingService switchingService) {
        this.switchingService = switchingService;
    }

    @McpTool(
            name = "switch_knowledge_base",
            description =
                    "Sets this account's active knowledge base by name, for the currently linked "
                            + "tenant. Every other knowledge-base tool (ask, search, ingest, "
                            + "evaluate, update_knowledge_base, delete_knowledge_base) then acts "
                            + "on this knowledge base automatically whenever its own "
                            + "knowledgeBaseId argument is omitted.")
    public KnowledgeBaseResponse switchKnowledgeBase(
            @McpToolParam(description = "Name of the knowledge base to switch to") String name) {
        McpUserPrincipal principal =
                (McpUserPrincipal)
                        SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return switchingService.switchKnowledgeBase(
                principal.getMcpUserId(), TenantContext.get(), name);
    }
}
