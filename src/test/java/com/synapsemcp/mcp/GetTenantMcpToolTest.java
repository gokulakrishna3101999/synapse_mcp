package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.TenantContext;
import com.synapsemcp.document.DocumentStatusSummary;
import com.synapsemcp.tenant.TenantDetailResponse;
import com.synapsemcp.tenant.TenantService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GetTenantMcpToolTest {

    private final TenantService tenantService = mock(TenantService.class);
    private final GetTenantMcpTool tool = new GetTenantMcpTool(tenantService);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void delegatesToTenantServiceUsingTheCurrentTenantContext() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        TenantDetailResponse expected =
                new TenantDetailResponse(
                        tenantId, "Acme Corp", Instant.now(), 2, DocumentStatusSummary.EMPTY);
        when(tenantService.getTenantDetail(tenantId)).thenReturn(expected);

        TenantDetailResponse response = tool.getTenant();

        assertThat(response).isEqualTo(expected);
    }
}
