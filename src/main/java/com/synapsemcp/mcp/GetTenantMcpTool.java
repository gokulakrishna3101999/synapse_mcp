package com.synapsemcp.mcp;

import com.synapsemcp.common.TenantContext;
import com.synapsemcp.tenant.TenantDetailResponse;
import com.synapsemcp.tenant.TenantService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/**
 * mcp_plan.md Stage 2 {@code get_tenant} tool. Wraps {@link TenantService#getTenantDetail}
 * directly, per Grooming #2.
 */
@Component
public class GetTenantMcpTool {

    private final TenantService tenantService;

    public GetTenantMcpTool(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @McpTool(
            name = "get_tenant",
            description =
                    "Returns this account's tenant details: name, created date, knowledge base "
                            + "count, and a document-count/status-summary rollup across those "
                            + "knowledge bases.")
    public TenantDetailResponse getTenant() {
        return tenantService.getTenantDetail(TenantContext.get());
    }
}
