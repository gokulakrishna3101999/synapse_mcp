package com.synapsemcp.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.ProviderCredentials;
import com.synapsemcp.common.ProviderCredentialsCodec;
import com.synapsemcp.knowledgebase.KnowledgeBase;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfigRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

class ModelConfigServiceTest {

    private final ModelConfigRepository modelConfigRepository = mock(ModelConfigRepository.class);
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository =
            mock(KnowledgeBaseModelConfigRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ModelConfigService modelConfigService =
            new ModelConfigService(
                    modelConfigRepository,
                    tenantRepository,
                    knowledgeBaseModelConfigRepository,
                    eventPublisher);

    private final UUID tenantId = UUID.randomUUID();

    private Tenant tenantWithId(UUID id) {
        Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(id);
        return tenant;
    }

    @Test
    void createsANewModelConfigWhenNoneExistsAndPublishesUpdateEvent() {
        Tenant tenant = tenantWithId(tenantId);
        when(modelConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        when(tenantRepository.getReferenceById(tenantId)).thenReturn(tenant);
        when(knowledgeBaseModelConfigRepository.findByKnowledgeBase_Tenant_Id(tenantId))
                .thenReturn(List.of());
        when(modelConfigRepository.saveAndFlush(any(ModelConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ConfigureModelRequest request =
                new ConfigureModelRequest(
                        "openai",
                        "gpt-4o",
                        "openai",
                        "text-embedding-3-small",
                        "sk-chat",
                        "sk-embed");
        ModelConfigResponse response = modelConfigService.configureModel(tenantId, request);

        assertThat(response.tenantId()).isEqualTo(tenantId);
        assertThat(response.chatProvider()).isEqualTo("openai");
        assertThat(response.chatModel()).isEqualTo("gpt-4o");
        assertThat(response.embeddingProvider()).isEqualTo("openai");
        assertThat(response.embeddingModel()).isEqualTo("text-embedding-3-small");

        ArgumentCaptor<ModelConfigUpdatedEvent> eventCaptor =
                ArgumentCaptor.forClass(ModelConfigUpdatedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void updatesTheExistingModelConfigRowInPlaceRatherThanCreatingANewOne() {
        Tenant tenant = tenantWithId(tenantId);
        ModelConfig existing =
                ModelConfig.create(
                        tenant,
                        "ollama",
                        "llama3",
                        "ollama",
                        "nomic-embed-text",
                        ProviderCredentialsCodec.encode(new ProviderCredentials(null, null)));
        when(modelConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(existing));
        when(knowledgeBaseModelConfigRepository.findByKnowledgeBase_Tenant_Id(tenantId))
                .thenReturn(List.of());
        when(modelConfigRepository.saveAndFlush(any(ModelConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ConfigureModelRequest request =
                new ConfigureModelRequest(
                        "openai",
                        "gpt-4o",
                        "openai",
                        "text-embedding-3-small",
                        "sk-chat",
                        "sk-embed");
        ModelConfigResponse response = modelConfigService.configureModel(tenantId, request);

        assertThat(response.chatProvider()).isEqualTo("openai");
        assertThat(existing.getChatProvider()).isEqualTo("openai");
        assertThat(existing.getChatModel()).isEqualTo("gpt-4o");
        verify(tenantRepository, never()).getReferenceById(any());

        ArgumentCaptor<ModelConfig> savedCaptor = ArgumentCaptor.forClass(ModelConfig.class);
        verify(modelConfigRepository).saveAndFlush(savedCaptor.capture());
        assertThat(savedCaptor.getValue()).isSameAs(existing);
    }

    @Test
    void rejectsAnUnsupportedChatProviderWith422() {
        ConfigureModelRequest request =
                new ConfigureModelRequest(
                        "bogus", "some-model", "openai", "text-embedding-3-small", null, null);

        assertThatThrownBy(() -> modelConfigService.configureModel(tenantId, request))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    /**
     * Found live (audit session, 2026-07-17): this environment happens to have a real {@code
     * OPENAI_API_KEY} set in the server process's own environment, and the OpenAI Java SDK silently
     * falls back to it when a tenant's own {@code chatApiKey}/{@code embeddingApiKey} is blank - a
     * cross-tenant billing/security leak on any deployment where that env var happens to be set.
     * Rejecting a blank credential for key-requiring providers at write time closes this at the
     * source. Ollama needs no credential and stays exempt (covered by the ollama-based tests
     * above).
     */
    @Test
    void rejectsABlankChatApiKeyForAProviderThatRequiresOne() {
        ConfigureModelRequest request =
                new ConfigureModelRequest(
                        "openai", "gpt-4o", "ollama", "nomic-embed-text", null, null);

        assertThatThrownBy(() -> modelConfigService.configureModel(tenantId, request))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void rejectsABlankEmbeddingApiKeyForAProviderThatRequiresOne() {
        ConfigureModelRequest request =
                new ConfigureModelRequest(
                        "ollama", "llama3", "google-genai", "text-embedding-004", null, "");

        assertThatThrownBy(() -> modelConfigService.configureModel(tenantId, request))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void rejectsAnthropicAsAnEmbeddingProviderBecauseItHasNoEmbeddingsApi() {
        ConfigureModelRequest request =
                new ConfigureModelRequest(
                        "anthropic", "claude-opus", "anthropic", "some-model", null, null);

        assertThatThrownBy(() -> modelConfigService.configureModel(tenantId, request))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void getModelConfigReturnsTheStoredConfigWithoutCredentials() {
        Tenant tenant = tenantWithId(tenantId);
        ModelConfig stored =
                ModelConfig.create(
                        tenant,
                        "openai",
                        "gpt-4o",
                        "openai",
                        "text-embedding-3-small",
                        ProviderCredentialsCodec.encode(
                                new ProviderCredentials("sk-chat", "sk-embed")));
        when(modelConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(stored));

        ModelConfigResponse response = modelConfigService.getModelConfig(tenantId);

        assertThat(response.chatProvider()).isEqualTo("openai");
        assertThat(response.toString()).doesNotContain("sk-chat").doesNotContain("sk-embed");
    }

    @Test
    void getModelConfigThrows404WhenTenantHasNeverConfiguredOne() {
        when(modelConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> modelConfigService.getModelConfig(tenantId))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void groomingTwentyTwo_onlySyncsTheCredentialHalfMatchingTheChangedProvider() {
        Tenant tenant = tenantWithId(tenantId);
        ModelConfig existing =
                ModelConfig.create(
                        tenant,
                        "openai",
                        "gpt-4o-mini",
                        "openai",
                        "text-embedding-3-small",
                        ProviderCredentialsCodec.encode(
                                new ProviderCredentials("old-chat", "old-embed")));
        when(modelConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(existing));
        when(modelConfigRepository.saveAndFlush(any(ModelConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        KnowledgeBase kb = mock(KnowledgeBase.class);
        // Chat provider matches (openai), embedding provider does not (ollama) - only chatApiKey
        // syncs.
        KnowledgeBaseModelConfig matchingChatOnly =
                KnowledgeBaseModelConfig.create(
                        kb,
                        "openai",
                        "gpt-4o-mini",
                        "ollama",
                        "nomic-embed-text",
                        ProviderCredentialsCodec.encode(
                                new ProviderCredentials("kb-old-chat", "kb-old-embed")));
        // Neither provider matches - left completely untouched.
        KnowledgeBaseModelConfig noMatch =
                KnowledgeBaseModelConfig.create(
                        kb,
                        "anthropic",
                        "claude-opus",
                        "ollama",
                        "nomic-embed-text",
                        ProviderCredentialsCodec.encode(
                                new ProviderCredentials(
                                        "kb-untouched-chat", "kb-untouched-embed")));
        when(knowledgeBaseModelConfigRepository.findByKnowledgeBase_Tenant_Id(tenantId))
                .thenReturn(List.of(matchingChatOnly, noMatch));

        ConfigureModelRequest request =
                new ConfigureModelRequest(
                        "openai",
                        "gpt-4o",
                        "openai",
                        "text-embedding-3-small",
                        "new-chat",
                        "new-embed");
        modelConfigService.configureModel(tenantId, request);

        ProviderCredentials matchingChatOnlyCreds =
                ProviderCredentialsCodec.decode(matchingChatOnly.getProviderCredentials());
        assertThat(matchingChatOnlyCreds.chatApiKey()).isEqualTo("new-chat");
        assertThat(matchingChatOnlyCreds.embeddingApiKey()).isEqualTo("kb-old-embed");

        ProviderCredentials noMatchCreds =
                ProviderCredentialsCodec.decode(noMatch.getProviderCredentials());
        assertThat(noMatchCreds.chatApiKey()).isEqualTo("kb-untouched-chat");
        assertThat(noMatchCreds.embeddingApiKey()).isEqualTo("kb-untouched-embed");

        verify(knowledgeBaseModelConfigRepository).saveAll(List.of(matchingChatOnly, noMatch));
    }
}
