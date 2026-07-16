package com.synapsemcp.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TenantServiceTest {

    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
    private final TenantService tenantService =
            new TenantService(tenantRepository, apiKeyRepository);

    @Test
    void createsTenantAndIssuesAnApiKeyWhoseHashMatchesTheRawKeyReturned() {
        when(tenantRepository.save(any(Tenant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(apiKeyRepository.save(any(ApiKey.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CreateTenantResponse response = tenantService.createTenant("Acme Corp");

        assertThat(response.name()).isEqualTo("Acme Corp");
        assertThat(response.apiKey()).isNotBlank();

        ArgumentCaptor<ApiKey> apiKeyCaptor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository).save(apiKeyCaptor.capture());
        assertThat(apiKeyCaptor.getValue().getKeyHash())
                .isEqualTo(ApiKeyHasher.sha256Hex(response.apiKey()));
    }

    @Test
    void generatesADifferentApiKeyPerCall() {
        when(tenantRepository.save(any(Tenant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(apiKeyRepository.save(any(ApiKey.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CreateTenantResponse first = tenantService.createTenant("Tenant A");
        CreateTenantResponse second = tenantService.createTenant("Tenant B");

        assertThat(first.apiKey()).isNotEqualTo(second.apiKey());
    }
}
