package com.synapsemcp.embedding;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.ProviderCredentials;
import com.synapsemcp.common.ProviderCredentialsCodec;
import com.synapsemcp.tenant.ModelConfig;
import com.synapsemcp.tenant.ModelConfigRepository;
import com.synapsemcp.tenant.ModelConfigUpdatedEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Builds per-tenant {@link EmbeddingModel} instances directly via each provider's Spring AI
 * builder, reading provider/model/credentials exclusively from the tenant's {@code model_configs}
 * row (rag_plan.md Stage 2 Hard Rule). Unlike {@link com.synapsemcp.chat.ChatModelFactory}, the
 * model name *is* baked in at construction time here: Stage 3's dimension-derivation probe
 * (`plan.md` §9, 2026-07-16) calls the no-args {@code embed(String)} convenience method, which has
 * no per-call options parameter to attach a model name to - the provider-specific options set here
 * are the only place it can go.
 */
@Component
public class EmbeddingModelFactory {

    private final ModelConfigRepository modelConfigRepository;
    private final Map<UUID, EmbeddingModel> cache = new ConcurrentHashMap<>();

    public EmbeddingModelFactory(ModelConfigRepository modelConfigRepository) {
        this.modelConfigRepository = modelConfigRepository;
    }

    public EmbeddingModel getEmbeddingModel(UUID tenantId) {
        return cache.computeIfAbsent(tenantId, this::buildEmbeddingModel);
    }

    /**
     * {@code AFTER_COMMIT} - see {@link com.synapsemcp.chat.ChatModelFactory#onModelConfigUpdated}
     * for the full race this closes (eviction firing before the publishing transaction commits,
     * allowing a concurrent {@link #getEmbeddingModel} call to repopulate the cache with stale,
     * pre-update data).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onModelConfigUpdated(ModelConfigUpdatedEvent event) {
        cache.remove(event.getTenantId());
    }

    private EmbeddingModel buildEmbeddingModel(UUID tenantId) {
        ModelConfig modelConfig =
                modelConfigRepository
                        .findByTenantId(tenantId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.UNPROCESSABLE_ENTITY,
                                                "Unprocessable Entity",
                                                "model config not set for tenant"));
        ProviderCredentials credentials =
                ProviderCredentialsCodec.decode(modelConfig.getProviderCredentials());
        return switch (modelConfig.getEmbeddingProvider()) {
            case "openai" ->
                    OpenAiEmbeddingModel.builder()
                            .options(
                                    OpenAiEmbeddingOptions.builder()
                                            .apiKey(credentials.embeddingApiKey())
                                            .model(modelConfig.getEmbeddingModel())
                                            .build())
                            .build();
            case "ollama" ->
                    OllamaEmbeddingModel.builder()
                            .ollamaApi(OllamaApi.builder().build())
                            .options(
                                    OllamaEmbeddingOptions.builder()
                                            .model(modelConfig.getEmbeddingModel())
                                            .build())
                            .build();
            case "google-genai" ->
                    new GoogleGenAiTextEmbeddingModel(
                            GoogleGenAiEmbeddingConnectionDetails.builder()
                                    .apiKey(credentials.embeddingApiKey())
                                    .build(),
                            GoogleGenAiTextEmbeddingOptions.builder()
                                    .model(modelConfig.getEmbeddingModel())
                                    .build());
            default ->
                    throw new IllegalStateException(
                            "unsupported embedding provider: "
                                    + modelConfig.getEmbeddingProvider());
        };
    }
}
