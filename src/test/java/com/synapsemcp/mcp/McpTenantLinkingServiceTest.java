package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.tenant.CreateTenantResponse;
import com.synapsemcp.tenant.Tenant;
import com.synapsemcp.tenant.TenantRepository;
import com.synapsemcp.tenant.TenantService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class McpTenantLinkingServiceTest {

    private final TenantService tenantService = mock(TenantService.class);
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final McpUserRepository mcpUserRepository = mock(McpUserRepository.class);
    private final McpTenantLinkingService linkingService =
            new McpTenantLinkingService(tenantService, tenantRepository, mcpUserRepository);

    @Test
    void createsTheTenantAndLinksTheAccountWhenStillUnlinked() {
        UUID mcpUserId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        CreateTenantResponse response =
                new CreateTenantResponse(tenantId, "Acme Corp", "raw-api-key");
        Tenant tenant = mock(Tenant.class);
        when(tenantService.createTenant("Acme Corp")).thenReturn(response);
        when(tenantRepository.getReferenceById(tenantId)).thenReturn(tenant);
        when(mcpUserRepository.linkTenantIfUnlinked(mcpUserId, tenant)).thenReturn(1);

        CreateTenantResponse result = linkingService.createAndLinkTenant(mcpUserId, "Acme Corp");

        assertThat(result).isEqualTo(response);
    }

    /**
     * Grooming #16's race guard: a {@code 0}-row conditional update means a concurrent {@code
     * create_tenant} call already linked this account first - the whole transaction (including the
     * tenant/API-key just created above) must roll back, surfaced here as this exception.
     */
    @Test
    void throwsWhenTheAccountIsAlreadyLinkedByAConcurrentCall() {
        UUID mcpUserId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        CreateTenantResponse response =
                new CreateTenantResponse(tenantId, "Acme Corp", "raw-api-key");
        Tenant tenant = mock(Tenant.class);
        when(tenantService.createTenant("Acme Corp")).thenReturn(response);
        when(tenantRepository.getReferenceById(tenantId)).thenReturn(tenant);
        when(mcpUserRepository.linkTenantIfUnlinked(mcpUserId, tenant)).thenReturn(0);

        assertThatThrownBy(() -> linkingService.createAndLinkTenant(mcpUserId, "Acme Corp"))
                .isInstanceOf(McpToolAccessDeniedException.class)
                .hasMessage("This account is already linked to a tenant.");
    }

    @Test
    void neverAttemptsToLinkBeforeTheTenantIsActuallyCreated() {
        UUID mcpUserId = UUID.randomUUID();
        when(tenantService.createTenant(any()))
                .thenThrow(new RuntimeException("provisioning failed"));

        assertThatThrownBy(() -> linkingService.createAndLinkTenant(mcpUserId, "Acme Corp"))
                .isInstanceOf(RuntimeException.class);

        verify(mcpUserRepository, never()).linkTenantIfUnlinked(any(), any());
    }

    @Test
    void resolvesTheTenantReferenceFromTheJustCreatedTenantId() {
        UUID mcpUserId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        CreateTenantResponse response =
                new CreateTenantResponse(tenantId, "Acme Corp", "raw-api-key");
        Tenant tenant = mock(Tenant.class);
        when(tenantService.createTenant("Acme Corp")).thenReturn(response);
        when(tenantRepository.getReferenceById(tenantId)).thenReturn(tenant);
        when(mcpUserRepository.linkTenantIfUnlinked(eq(mcpUserId), eq(tenant))).thenReturn(1);

        linkingService.createAndLinkTenant(mcpUserId, "Acme Corp");

        verify(tenantRepository).getReferenceById(tenantId);
        verify(mcpUserRepository).linkTenantIfUnlinked(mcpUserId, tenant);
    }
}
