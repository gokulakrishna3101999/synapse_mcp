package com.synapsemcp.mcp;

import com.synapsemcp.tenant.TenantDetailResponse;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * User-requested (2026-07-21) {@code switch_tenant} tool. Moves this account to a different,
 * already-existing tenant - never creates one (that remains {@code create_tenant}'s sole purpose,
 * unchanged). Usable both from a brand-new, never-linked account (an alternative to {@code
 * create_tenant} for attaching directly to a tenant you already hold the key for) and from an
 * already-linked account wanting to move elsewhere - {@link McpToolAccessAspect} allow-lists this
 * tool the same way it does {@code create_tenant}. Wraps {@link McpTenantSwitchingService}
 * directly, per Grooming #2 (MCP tools call domain services, never REST controllers).
 */
@Component
public class SwitchTenantMcpTool {

    private final McpTenantSwitchingService switchingService;

    SwitchTenantMcpTool(McpTenantSwitchingService switchingService) {
        this.switchingService = switchingService;
    }

    @McpTool(
            name = McpToolAccessAspect.SWITCH_TENANT_TOOL_NAME,
            description =
                    "Switches this account to a different, already-existing tenant by proving"
                            + " ownership of its own API key. Every other tool then acts on the"
                            + " newly-switched tenant until you switch again or authenticate as a"
                            + " different account. Does not create a tenant - use create_tenant"
                            + " for that. Usable immediately after registering, even before ever"
                            + " being linked to any tenant.")
    public TenantDetailResponse switchTenant(
            @McpToolParam(description = "Id of the tenant to switch to") String tenantId,
            @McpToolParam(
                            description =
                                    "That tenant's own API key, proving you're authorized to"
                                            + " switch to it")
                    String apiKey) {
        var principal =
                (McpUserPrincipal)
                        SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        UUID id = McpToolInputs.parseUuid(tenantId, "tenantId");
        return switchingService.switchTenant(principal.getMcpUserId(), id, apiKey);
    }
}
