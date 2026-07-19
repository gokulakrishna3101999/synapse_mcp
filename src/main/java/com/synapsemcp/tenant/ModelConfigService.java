package com.synapsemcp.tenant;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.ProviderCredentials;
import com.synapsemcp.common.ProviderCredentialsCodec;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfigRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** rag_plan.md Stage 2: create-or-update a tenant's {@code model_configs} row. */
@Service
public class ModelConfigService {

    private static final Set<String> CHAT_PROVIDERS =
            Set.of("openai", "anthropic", "ollama", "google-genai");
    private static final Set<String> EMBEDDING_PROVIDERS =
            Set.of("openai", "ollama", "google-genai");

    /**
     * Providers whose Java SDK requires an explicit credential to construct a client at all -
     * unlike Ollama (no auth), all three fall back to reading their own provider-specific API-key
     * environment variable when the caller passes a blank/null key (confirmed live for OpenAI:
     * {@code OPENAI_API_KEY}, via {@code com.openai.core.ClientOptions}). Found by an audit session
     * (2026-07-17) that this environment happens to have a real {@code OPENAI_API_KEY} set in the
     * server process's own environment - meaning a tenant leaving {@code chatApiKey}/{@code
     * embeddingApiKey} blank would silently use, and get billed against, *the operator's own key*,
     * with zero visibility. Rejecting a blank credential for these three providers at write time
     * closes the gap at the source, before a tenant can ever reach a state where the SDK's fallback
     * could trigger.
     */
    private static final Set<String> PROVIDERS_REQUIRING_CREDENTIALS =
            Set.of("openai", "anthropic", "google-genai");

    private final ModelConfigRepository modelConfigRepository;
    private final TenantRepository tenantRepository;
    private final KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ModelConfigService(
            ModelConfigRepository modelConfigRepository,
            TenantRepository tenantRepository,
            KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository,
            ApplicationEventPublisher eventPublisher) {
        this.modelConfigRepository = modelConfigRepository;
        this.tenantRepository = tenantRepository;
        this.knowledgeBaseModelConfigRepository = knowledgeBaseModelConfigRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ModelConfigResponse configureModel(UUID tenantId, ConfigureModelRequest request) {
        validateProvider(request.chatProvider(), CHAT_PROVIDERS, "chat provider");
        validateProvider(request.embeddingProvider(), EMBEDDING_PROVIDERS, "embedding provider");
        requireCredentialIfProviderNeedsOne(
                request.chatProvider(), request.chatApiKey(), "chatApiKey");
        requireCredentialIfProviderNeedsOne(
                request.embeddingProvider(), request.embeddingApiKey(), "embeddingApiKey");

        ProviderCredentials credentials =
                new ProviderCredentials(request.chatApiKey(), request.embeddingApiKey());
        String encodedCredentials = ProviderCredentialsCodec.encode(credentials);

        ModelConfig modelConfig = modelConfigRepository.findByTenantId(tenantId).orElse(null);
        if (modelConfig == null) {
            Tenant tenant = tenantRepository.getReferenceById(tenantId);
            modelConfig =
                    ModelConfig.create(
                            tenant,
                            request.chatProvider(),
                            request.chatModel(),
                            request.embeddingProvider(),
                            request.embeddingModel(),
                            encodedCredentials);
        } else {
            modelConfig.setChatProvider(request.chatProvider());
            modelConfig.setChatModel(request.chatModel());
            modelConfig.setEmbeddingProvider(request.embeddingProvider());
            modelConfig.setEmbeddingModel(request.embeddingModel());
            modelConfig.setProviderCredentials(encodedCredentials);
        }
        // saveAndFlush, not save: @CreationTimestamp is only populated by Hibernate at flush time,
        // and
        // toResponse() below reads getCreatedAt() from this same in-memory instance immediately - a
        // plain save() would return it as null for a brand-new row (verified empirically: PUT
        // response
        // showed createdAt: null while an immediately-following GET showed the real timestamp).
        modelConfig = modelConfigRepository.saveAndFlush(modelConfig);

        syncCredentialsToKnowledgeBaseConfigs(
                tenantId, request.chatProvider(), request.embeddingProvider(), credentials);

        eventPublisher.publishEvent(new ModelConfigUpdatedEvent(this, tenantId));

        return toResponse(modelConfig);
    }

    @Transactional(readOnly = true)
    public ModelConfigResponse getModelConfig(UUID tenantId) {
        ModelConfig modelConfig =
                modelConfigRepository
                        .findByTenantId(tenantId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.NOT_FOUND,
                                                "Not Found",
                                                "model config not found for tenant"));
        return toResponse(modelConfig);
    }

    /**
     * Grooming #22 - API Key Auto-Sync: a knowledge_base's model/embedding *names* are locked
     * forever at creation (Stage 3), but its provider *credentials* stay in sync with the tenant's
     * latest global ones for any provider it shares. Only the matching half (chat or embedding) of
     * each KB config's credentials is touched - the other half, tied to a provider that didn't
     * change, is left exactly as it was.
     */
    private void syncCredentialsToKnowledgeBaseConfigs(
            UUID tenantId,
            String chatProvider,
            String embeddingProvider,
            ProviderCredentials newCredentials) {
        List<KnowledgeBaseModelConfig> kbConfigs =
                knowledgeBaseModelConfigRepository.findByKnowledgeBase_Tenant_Id(tenantId);
        for (KnowledgeBaseModelConfig kbConfig : kbConfigs) {
            boolean chatMatches = kbConfig.getChatProvider().equals(chatProvider);
            boolean embeddingMatches = kbConfig.getEmbeddingProvider().equals(embeddingProvider);
            if (!chatMatches && !embeddingMatches) {
                continue;
            }
            ProviderCredentials existing =
                    ProviderCredentialsCodec.decode(kbConfig.getProviderCredentials());
            String chatApiKey = chatMatches ? newCredentials.chatApiKey() : existing.chatApiKey();
            String embeddingApiKey =
                    embeddingMatches
                            ? newCredentials.embeddingApiKey()
                            : existing.embeddingApiKey();
            kbConfig.setProviderCredentials(
                    ProviderCredentialsCodec.encode(
                            new ProviderCredentials(chatApiKey, embeddingApiKey)));
        }
        knowledgeBaseModelConfigRepository.saveAll(kbConfigs);
    }

    private void requireCredentialIfProviderNeedsOne(
            String provider, String apiKey, String fieldName) {
        if (PROVIDERS_REQUIRING_CREDENTIALS.contains(provider)
                && (apiKey == null || apiKey.isBlank())) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unprocessable Entity",
                    fieldName + " is required for provider " + provider);
        }
    }

    private void validateProvider(String provider, Set<String> allowed, String fieldName) {
        if (!allowed.contains(provider)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unprocessable Entity",
                    "invalid " + fieldName + ": " + provider);
        }
    }

    private ModelConfigResponse toResponse(ModelConfig modelConfig) {
        return new ModelConfigResponse(
                modelConfig.getTenantId(),
                modelConfig.getChatProvider(),
                modelConfig.getChatModel(),
                modelConfig.getEmbeddingProvider(),
                modelConfig.getEmbeddingModel(),
                modelConfig.getCreatedAt());
    }
}
