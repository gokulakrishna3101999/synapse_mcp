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

        ProviderCredentials credentials =
                new ProviderCredentials(request.chatApiKey(), request.embeddingApiKey());
        String encodedCredentials = ProviderCredentialsCodec.encode(credentials);

        ModelConfig modelConfig = modelConfigRepository.findByTenantId(tenantId).orElse(null);
        if (modelConfig == null) {
            Tenant tenant = tenantRepository.getReferenceById(tenantId);
            modelConfig =
                    new ModelConfig(
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
                modelConfig.getTenant().getId(),
                modelConfig.getChatProvider(),
                modelConfig.getChatModel(),
                modelConfig.getEmbeddingProvider(),
                modelConfig.getEmbeddingModel(),
                modelConfig.getCreatedAt());
    }
}
