package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.document.DocumentStatusSummary;
import com.synapsemcp.tenant.TenantDetailResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SwitchTenantMcpToolTest {

    private final McpTenantSwitchingService switchingService =
            mock(McpTenantSwitchingService.class);
    private final SwitchTenantMcpTool tool = new SwitchTenantMcpTool(switchingService);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void delegatesToTheSwitchingServiceUsingTheAuthenticatedPrincipalsMcpUserId() {
        UUID mcpUserId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        authenticateAs(mcpUserId);
        TenantDetailResponse expected =
                new TenantDetailResponse(
                        tenantId, "Acme Corp", Instant.now(), 1, DocumentStatusSummary.EMPTY);
        when(switchingService.switchTenant(eq(mcpUserId), eq(tenantId), eq("the-api-key")))
                .thenReturn(expected);

        TenantDetailResponse result = tool.switchTenant(tenantId.toString(), "the-api-key");

        assertThat(result).isEqualTo(expected);
        verify(switchingService).switchTenant(mcpUserId, tenantId, "the-api-key");
    }

    @Test
    void rejectsAMalformedTenantIdBeforeEverCallingTheSwitchingService() {
        authenticateAs(UUID.randomUUID());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> tool.switchTenant("not-a-uuid", "some-key"))
                .isInstanceOf(com.synapsemcp.common.ApiException.class);
    }

    private void authenticateAs(UUID mcpUserId) {
        McpUserPrincipal principal = new McpUserPrincipal(mcpUserId, "alice", "hash", null, null);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities()));
    }
}
