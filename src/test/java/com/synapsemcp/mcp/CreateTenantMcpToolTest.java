package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.tenant.CreateTenantResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CreateTenantMcpToolTest {

    private final McpTenantLinkingService linkingService = mock(McpTenantLinkingService.class);
    private final CreateTenantMcpTool tool = new CreateTenantMcpTool(linkingService);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void delegatesToTheLinkingServiceUsingTheAuthenticatedPrincipalsMcpUserId() {
        UUID mcpUserId = UUID.randomUUID();
        authenticateAs(mcpUserId);
        CreateTenantResponse expected =
                new CreateTenantResponse(UUID.randomUUID(), "Acme Corp", "raw-api-key");
        when(linkingService.createAndLinkTenant(eq(mcpUserId), eq("Acme Corp")))
                .thenReturn(expected);

        CreateTenantResponse result = tool.createTenant("Acme Corp");

        assertThat(result).isEqualTo(expected);
        verify(linkingService).createAndLinkTenant(mcpUserId, "Acme Corp");
    }

    private void authenticateAs(UUID mcpUserId) {
        McpUserPrincipal principal = new McpUserPrincipal(mcpUserId, "alice", "hash", null);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities()));
    }
}
