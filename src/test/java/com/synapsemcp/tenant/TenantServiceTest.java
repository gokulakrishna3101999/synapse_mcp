package com.synapsemcp.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.document.DocumentStatusSummary;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
import com.synapsemcp.knowledgebase.KnowledgeBaseService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TenantServiceTest {

    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final TenantService tenantService =
            new TenantService(tenantRepository, apiKeyRepository, knowledgeBaseService);

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

    @Test
    void getTenantDetailSumsEachKnowledgeBasesDocumentStatusSummary() {
        UUID tenantId = UUID.randomUUID();
        Tenant tenant = new Tenant("Acme Corp");
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(knowledgeBaseService.listKnowledgeBases(tenantId))
                .thenReturn(
                        List.of(
                                new KnowledgeBaseResponse(
                                        UUID.randomUUID(),
                                        "KB1",
                                        1536,
                                        new DocumentStatusSummary(1, 2, 3, 0)),
                                new KnowledgeBaseResponse(
                                        UUID.randomUUID(),
                                        "KB2",
                                        1536,
                                        new DocumentStatusSummary(0, 0, 5, 1))));

        TenantDetailResponse response = tenantService.getTenantDetail(tenantId);

        assertThat(response.knowledgeBaseCount()).isEqualTo(2);
        assertThat(response.documentStatusSummary())
                .isEqualTo(new DocumentStatusSummary(1, 2, 8, 1));
    }

    @Test
    void getTenantDetailThrows404ForUnknownTenant() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.getTenantDetail(tenantId))
                .isInstanceOf(ApiException.class);
    }
}
