package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.TenantContext;
import com.synapsemcp.document.DocumentStatusSummary;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SwitchKnowledgeBaseMcpToolTest {

    private final McpActiveKnowledgeBaseSwitchingService switchingService =
            mock(McpActiveKnowledgeBaseSwitchingService.class);
    private final SwitchKnowledgeBaseMcpTool tool =
            new SwitchKnowledgeBaseMcpTool(switchingService);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void delegatesToTheSwitchingServiceUsingTheAuthenticatedPrincipalsMcpUserIdAndCurrentTenant() {
        UUID mcpUserId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        authenticateAs(mcpUserId);
        TenantContext.set(tenantId);
        KnowledgeBaseResponse expected =
                new KnowledgeBaseResponse(kbId, "My KB", 1536, DocumentStatusSummary.EMPTY);
        when(switchingService.switchKnowledgeBase(eq(mcpUserId), eq(tenantId), eq("My KB")))
                .thenReturn(expected);

        KnowledgeBaseResponse result = tool.switchKnowledgeBase("My KB");

        assertThat(result).isEqualTo(expected);
        verify(switchingService).switchKnowledgeBase(mcpUserId, tenantId, "My KB");
    }

    private void authenticateAs(UUID mcpUserId) {
        McpUserPrincipal principal = new McpUserPrincipal(mcpUserId, "alice", "hash", null, null);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities()));
    }
}
