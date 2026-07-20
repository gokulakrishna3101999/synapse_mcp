package com.synapsemcp.mcp;

import com.synapsemcp.tenant.CreateTenantResponse;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * mcp_plan.md Stage 2's {@code create_tenant} tool, built now (Stage 1) since it's the one tool
 * that must exist before an account is linked at all (Grooming #13) - the rest of Stage 2's tools
 * follow in a later session. Wraps {@link McpTenantLinkingService} directly, per Grooming #2 (MCP
 * tools call domain services, never REST controllers).
 */
@Component
public class CreateTenantMcpTool {

    private final McpTenantLinkingService linkingService;

    public CreateTenantMcpTool(McpTenantLinkingService linkingService) {
        this.linkingService = linkingService;
    }

    @McpTool(
            name = McpToolAccessAspect.CREATE_TENANT_TOOL_NAME,
            description =
                    "Provisions a new tenant for this account and returns its tenant id and "
                            + "initial API key. Only usable once per account - fails if this "
                            + "account is already linked to a tenant. Every other tool requires "
                            + "this to have been called first.")
    public CreateTenantResponse createTenant(
            @McpToolParam(description = "Human-readable name for the new tenant", required = true)
                    String name) {
        var principal =
                (McpUserPrincipal)
                        SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return linkingService.createAndLinkTenant(principal.getMcpUserId(), name);
    }
}
