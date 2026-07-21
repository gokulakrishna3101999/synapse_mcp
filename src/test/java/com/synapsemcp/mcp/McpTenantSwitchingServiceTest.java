package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.tenant.ApiKeyHasher;
import com.synapsemcp.tenant.ApiKeyRepository;
import com.synapsemcp.tenant.Tenant;
import com.synapsemcp.tenant.TenantDetailResponse;
import com.synapsemcp.tenant.TenantRepository;
import com.synapsemcp.tenant.TenantService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class McpTenantSwitchingServiceTest {

    private final ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final McpUserRepository mcpUserRepository = mock(McpUserRepository.class);
    private final McpUserTenantLinkRepository linkRepository =
            mock(McpUserTenantLinkRepository.class);
    private final TenantService tenantService = mock(TenantService.class);
    private final McpTenantSwitchingService switchingService =
            new McpTenantSwitchingService(
                    apiKeyRepository,
                    tenantRepository,
                    mcpUserRepository,
                    linkRepository,
                    tenantService);

    @Test
    void switchesToTheTenantWhenTheApiKeyGenuinelyBelongsToIt() {
        UUID mcpUserId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String apiKey = "raw-api-key";
        Tenant tenant = mock(Tenant.class);
        TenantDetailResponse expected =
                new TenantDetailResponse(
                        tenantId,
                        "Acme Corp",
                        Instant.now(),
                        1,
                        com.synapsemcp.document.DocumentStatusSummary.EMPTY);
        when(apiKeyRepository.findTenantIdByKeyHash(ApiKeyHasher.sha256Hex(apiKey)))
                .thenReturn(Optional.of(tenantId));
        when(tenantRepository.getReferenceById(tenantId)).thenReturn(tenant);
        when(tenantService.getTenantDetail(tenantId)).thenReturn(expected);

        TenantDetailResponse result = switchingService.switchTenant(mcpUserId, tenantId, apiKey);

        assertThat(result).isEqualTo(expected);
        verify(mcpUserRepository).switchTenant(mcpUserId, tenant);
    }

    @Test
    void rejectsAnApiKeyThatDoesNotResolveToAnyTenant() {
        UUID mcpUserId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(apiKeyRepository.findTenantIdByKeyHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> switchingService.switchTenant(mcpUserId, tenantId, "wrong-key"))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(mcpUserRepository, never()).switchTenant(any(), any());
    }

    @Test
    void rejectsAnApiKeyThatBelongsToADifferentTenantThanClaimed() {
        UUID mcpUserId = UUID.randomUUID();
        UUID claimedTenantId = UUID.randomUUID();
        UUID actualTenantId = UUID.randomUUID();
        String apiKey = "someone-elses-key";
        when(apiKeyRepository.findTenantIdByKeyHash(ApiKeyHasher.sha256Hex(apiKey)))
                .thenReturn(Optional.of(actualTenantId));

        assertThatThrownBy(() -> switchingService.switchTenant(mcpUserId, claimedTenantId, apiKey))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(mcpUserRepository, never()).switchTenant(any(), any());
    }

    /**
     * mcp_plan.md Grooming #26: a brand-new (never-before-touched) tenant only consumes a slot
     * while the account is still under the 5-tenant cap - this account has 4 already, so a 5th is
     * still allowed and gets recorded as a new history row.
     */
    @Test
    void allowsAndRecordsANewTenantWhenStillUnderTheCap() {
        UUID mcpUserId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String apiKey = "raw-api-key";
        Tenant tenant = mock(Tenant.class);
        when(apiKeyRepository.findTenantIdByKeyHash(ApiKeyHasher.sha256Hex(apiKey)))
                .thenReturn(Optional.of(tenantId));
        when(linkRepository.existsByMcpUserIdAndTenantId(mcpUserId, tenantId)).thenReturn(false);
        when(linkRepository.countByMcpUserId(mcpUserId)).thenReturn(4L);
        when(tenantRepository.getReferenceById(tenantId)).thenReturn(tenant);
        when(tenantService.getTenantDetail(tenantId))
                .thenReturn(
                        new TenantDetailResponse(
                                tenantId,
                                "Acme Corp",
                                Instant.now(),
                                1,
                                com.synapsemcp.document.DocumentStatusSummary.EMPTY));

        switchingService.switchTenant(mcpUserId, tenantId, apiKey);

        verify(linkRepository)
                .save(
                        org.mockito.ArgumentMatchers.argThat(
                                link ->
                                        link.getMcpUserId().equals(mcpUserId)
                                                && link.getTenantId().equals(tenantId)));
        verify(mcpUserRepository).switchTenant(mcpUserId, tenant);
    }

    /**
     * The 5th tenant already used up the cap - a 6th, never-before-touched tenant is rejected
     * before any switch happens.
     */
    @Test
    void rejectsABrandNewTenantOnceTheAccountHasReachedFiveDistinctTenants() {
        UUID mcpUserId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String apiKey = "raw-api-key";
        when(apiKeyRepository.findTenantIdByKeyHash(ApiKeyHasher.sha256Hex(apiKey)))
                .thenReturn(Optional.of(tenantId));
        when(linkRepository.existsByMcpUserIdAndTenantId(mcpUserId, tenantId)).thenReturn(false);
        when(linkRepository.countByMcpUserId(mcpUserId)).thenReturn(5L);

        assertThatThrownBy(() -> switchingService.switchTenant(mcpUserId, tenantId, apiKey))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("maximum of 5 tenants");
        verify(linkRepository, never()).save(any());
        verify(mcpUserRepository, never()).switchTenant(any(), any());
    }

    /**
     * Switching back to a tenant already in this account's history is always free, even when the
     * account is already sitting at the 5-tenant cap - it isn't a new tenant, so it never consumes
     * (or needs to re-check) a slot.
     */
    @Test
    void allowsSwitchingBackToAnAlreadyTouchedTenantEvenAtTheCap() {
        UUID mcpUserId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String apiKey = "raw-api-key";
        Tenant tenant = mock(Tenant.class);
        when(apiKeyRepository.findTenantIdByKeyHash(ApiKeyHasher.sha256Hex(apiKey)))
                .thenReturn(Optional.of(tenantId));
        when(linkRepository.existsByMcpUserIdAndTenantId(mcpUserId, tenantId)).thenReturn(true);
        when(linkRepository.countByMcpUserId(mcpUserId)).thenReturn(5L);
        when(tenantRepository.getReferenceById(tenantId)).thenReturn(tenant);
        when(tenantService.getTenantDetail(tenantId))
                .thenReturn(
                        new TenantDetailResponse(
                                tenantId,
                                "Acme Corp",
                                Instant.now(),
                                1,
                                com.synapsemcp.document.DocumentStatusSummary.EMPTY));

        switchingService.switchTenant(mcpUserId, tenantId, apiKey);

        verify(linkRepository, never()).save(any());
        verify(mcpUserRepository).switchTenant(mcpUserId, tenant);
    }
}
