package com.synapsemcp.chat;

import com.google.genai.Client;
import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.ProviderCredentials;
import com.synapsemcp.common.ProviderCredentialsCodec;
import com.synapsemcp.tenant.ModelConfig;
import com.synapsemcp.tenant.ModelConfigRepository;
import com.synapsemcp.tenant.ModelConfigUpdatedEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Builds per-tenant {@link ChatModel} instances directly via each provider's Spring AI builder,
 * reading provider/credentials exclusively from the tenant's {@code model_configs} row (rag_plan.md
 * Stage 2 Hard Rule) - never from {@code spring.ai.*} autoconfiguration. Only connection-level
 * options (API key, client) are set here; the model name is attached per-call via {@link
 * #optionsFor}'s generic {@link ChatOptions}, not baked into provider-specific options at
 * construction (Grooming #15) - avoids every provider's own enum-typed {@code model()} setter (e.g.
 * Anthropic's {@code com.anthropic.models.messages.Model}, Ollama's {@code OllamaModel}) entirely.
 */
@Component
public class ChatModelFactory {

    private final ModelConfigRepository modelConfigRepository;
    private final Map<UUID, ChatModel> cache = new ConcurrentHashMap<>();

    public ChatModelFactory(ModelConfigRepository modelConfigRepository) {
        this.modelConfigRepository = modelConfigRepository;
    }

    public ChatModel getChatModel(UUID tenantId) {
        return cache.computeIfAbsent(tenantId, id -> buildChatModel(resolve(id)));
    }

    public ChatOptions optionsFor(UUID tenantId) {
        return ChatOptions.builder().model(resolve(tenantId).getChatModel()).build();
    }

    /**
     * {@code AFTER_COMMIT}, not the default (immediate, pre-commit) {@code @EventListener}: {@code
     * ModelConfigService.configureModel()} publishes this event synchronously from inside its own
     * {@code @Transactional} method body, before the transaction actually commits. An immediate
     * listener would evict the cache mid-transaction, leaving a window where a concurrent {@link
     * #getChatModel} call rebuilds the entry from the still-uncommitted (old) {@code model_configs}
     * row under READ_COMMITTED - and that stale entry would then never be evicted again. Deferring
     * to {@code AFTER_COMMIT} closes the window: by the time eviction happens, the new row is
     * already durable and visible to any concurrent read that repopulates the cache. Always fires
     * within a transaction in practice ({@code configureModel} is the only publisher and is always
     * {@code @Transactional}), so the default {@code fallbackExecution = false} is fine here.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onModelConfigUpdated(ModelConfigUpdatedEvent event) {
        cache.remove(event.getTenantId());
    }

    private ModelConfig resolve(UUID tenantId) {
        return modelConfigRepository
                .findByTenantId(tenantId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        HttpStatus.UNPROCESSABLE_ENTITY,
                                        "Unprocessable Entity",
                                        "model config not set for tenant"));
    }

    private ChatModel buildChatModel(ModelConfig modelConfig) {
        ProviderCredentials credentials =
                ProviderCredentialsCodec.decode(modelConfig.getProviderCredentials());
        return switch (modelConfig.getChatProvider()) {
            case "openai" ->
                    OpenAiChatModel.builder()
                            .options(
                                    OpenAiChatOptions.builder()
                                            .apiKey(credentials.chatApiKey())
                                            .build())
                            .build();
            case "anthropic" ->
                    AnthropicChatModel.builder()
                            .options(
                                    AnthropicChatOptions.builder()
                                            .apiKey(credentials.chatApiKey())
                                            .build())
                            .build();
            case "ollama" ->
                    OllamaChatModel.builder().ollamaApi(OllamaApi.builder().build()).build();
            case "google-genai" ->
                    GoogleGenAiChatModel.builder()
                            .genAiClient(Client.builder().apiKey(credentials.chatApiKey()).build())
                            .build();
            default ->
                    throw new IllegalStateException(
                            "unsupported chat provider: " + modelConfig.getChatProvider());
        };
    }
}
