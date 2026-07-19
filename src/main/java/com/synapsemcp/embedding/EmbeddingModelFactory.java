package com.synapsemcp.embedding;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.ProviderCredentials;
import com.synapsemcp.common.ProviderCredentialsCodec;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import com.synapsemcp.tenant.ModelConfig;
import com.synapsemcp.tenant.ModelConfigRepository;
import com.synapsemcp.tenant.ModelConfigUpdatedEvent;
import java.time.Duration;
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
import org.springframework.beans.factory.annotation.Value;
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
 *
 * <p>See {@link com.synapsemcp.chat.ChatModelFactory}'s Javadoc for why {@link #providerTimeout} is
 * explicitly set on OpenAI/Google GenAI here - this is the exact factory whose dimension-derivation
 * probe (Stage 3) surfaced the risk live: a slow/unresponsive provider during {@code
 * KnowledgeBaseService.probeEmbeddingDimension} would otherwise hold a Tomcat worker thread for up
 * to the SDK's default of 10 minutes (OpenAI) or indefinitely (Google GenAI, no default at all).
 *
 * <p>{@code final} - see {@link com.synapsemcp.chat.ChatModelFactory}'s Javadoc for the full
 * rationale ({@code CT_CONSTRUCTOR_THROW}, SpotBugs pass, `plan.md` §9 2026-07-17) and the
 * empirical confirmation that {@code @MockitoBean} (used on this exact class in {@code
 * KnowledgeBaseIntegrationTest}) still works with a {@code final} class under Spring Boot's test
 * infrastructure.
 */
@Component
public final class EmbeddingModelFactory {

    private final ModelConfigRepository modelConfigRepository;
    private final Duration providerTimeout;
    private final Map<UUID, EmbeddingModel> cache = new ConcurrentHashMap<>();

    public EmbeddingModelFactory(
            ModelConfigRepository modelConfigRepository,
            @Value("${synapsemcp.provider.timeout-seconds:30}") long providerTimeoutSeconds) {
        this.modelConfigRepository = modelConfigRepository;
        this.providerTimeout = requirePositiveTimeout(providerTimeoutSeconds);
    }

    /**
     * See {@link com.synapsemcp.chat.ChatModelFactory#requirePositiveTimeout} - same rationale: a
     * {@code 0} value means "no timeout" to OkHttp, not "fail instantly," so it's rejected at
     * startup rather than silently disabling this class's timeout fix.
     */
    private static Duration requirePositiveTimeout(long seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException(
                    "synapsemcp.provider.timeout-seconds must be positive (0 means \"no timeout\" to"
                            + " the underlying HTTP client, not \"fail instantly\"), got: "
                            + seconds);
        }
        return Duration.ofSeconds(seconds);
    }

    public EmbeddingModel getEmbeddingModel(UUID tenantId) {
        return cache.computeIfAbsent(tenantId, this::buildEmbeddingModel);
    }

    /**
     * rag_plan.md Stage 5c: ingestion reads exclusively from the knowledge_base's own {@code
     * knowledge_base_model_configs} snapshot (Grooming #23), never the tenant's live global config
     * - a KB's model is locked forever at creation, only its credentials stay synced (Grooming
     * #22). Deliberately <b>not cached</b>, unlike {@link #getEmbeddingModel(UUID)}: ingestion is
     * an async background job (at most once per document, not a hot synchronous request path), so
     * the cost of rebuilding a client per call is negligible next to the actual embedding API calls
     * it's about to make - and skipping a cache sidesteps needing a whole second cache-eviction
     * event/listener pair (there is currently no {@code KnowledgeBaseModelConfigUpdatedEvent} for
     * the credential-sync writes {@code ModelConfigService.syncCredentialsToKnowledgeBaseConfigs}
     * makes) just to keep it correct.
     */
    public EmbeddingModel getEmbeddingModelForKnowledgeBase(KnowledgeBaseModelConfig kbConfig) {
        return buildEmbeddingModel(
                kbConfig.getEmbeddingProvider(),
                kbConfig.getEmbeddingModel(),
                kbConfig.getProviderCredentials());
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
        return buildEmbeddingModel(
                modelConfig.getEmbeddingProvider(),
                modelConfig.getEmbeddingModel(),
                modelConfig.getProviderCredentials());
    }

    /**
     * Shared by both the tenant-scoped, cached path ({@link #buildEmbeddingModel(UUID)}) and the
     * knowledge_base-scoped, uncached path ({@link #getEmbeddingModelForKnowledgeBase}) - one copy
     * of the provider-construction switch, since {@link ModelConfig} and {@link
     * KnowledgeBaseModelConfig} share the same provider/model/credentials shape but aren't
     * otherwise related types.
     */
    private EmbeddingModel buildEmbeddingModel(
            String embeddingProvider, String embeddingModelName, String encodedCredentials) {
        ProviderCredentials credentials = ProviderCredentialsCodec.decode(encodedCredentials);
        return switch (embeddingProvider) {
            case "openai" ->
                    OpenAiEmbeddingModel.builder()
                            .options(
                                    OpenAiEmbeddingOptions.builder()
                                            .apiKey(credentials.embeddingApiKey())
                                            .model(embeddingModelName)
                                            .build())
                            .httpClientBuilderCustomizer(
                                    builder -> builder.timeout(providerTimeout))
                            .build();
            case "ollama" ->
                    OllamaEmbeddingModel.builder()
                            .ollamaApi(OllamaApi.builder().build())
                            .options(
                                    OllamaEmbeddingOptions.builder()
                                            .model(embeddingModelName)
                                            .build())
                            .build();
            case "google-genai" ->
                    new GoogleGenAiTextEmbeddingModel(
                            GoogleGenAiEmbeddingConnectionDetails.builder()
                                    .genAiClient(
                                            Client.builder()
                                                    .apiKey(credentials.embeddingApiKey())
                                                    .httpOptions(
                                                            HttpOptions.builder()
                                                                    .timeout(
                                                                            (int)
                                                                                    providerTimeout
                                                                                            .toMillis())
                                                                    .build())
                                                    .build())
                                    .build(),
                            GoogleGenAiTextEmbeddingOptions.builder()
                                    .model(embeddingModelName)
                                    .build());
            default ->
                    throw new IllegalStateException(
                            "unsupported embedding provider: " + embeddingProvider);
        };
    }
}
