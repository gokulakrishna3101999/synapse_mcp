package com.synapsemcp.chat;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.ProviderCredentials;
import com.synapsemcp.common.ProviderCredentialsCodec;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import com.synapsemcp.tenant.ModelConfig;
import com.synapsemcp.tenant.ModelConfigRepository;
import com.synapsemcp.tenant.ModelConfigUpdatedEvent;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Builds per-tenant {@link ChatModel} instances directly via each provider's Spring AI builder,
 * reading provider/credentials exclusively from the tenant's {@code model_configs} row (rag_plan.md
 * Stage 2 Hard Rule) - never from {@code spring.ai.*} autoconfiguration. Only connection-level
 * options (API key, client) are set here; the model name is attached per-call via {@link
 * #optionsFor}/{@link #optionsForKnowledgeBase}.
 *
 * <p><b>{@link #optionsFor}/{@link #optionsForKnowledgeBase} build a provider-specific concrete
 * {@code *ChatOptions} instance (`plan.md` §9 2026-07-18 - corrects and supersedes Grooming #15,
 * below), not the generic {@link ChatOptions#builder()}.</b> Found live, not theorized: a real
 * end-to-end smoke test against genuine OpenAI credentials threw {@code ClassCastException: class
 * org.springframework.ai.chat.prompt.DefaultChatOptions cannot be cast to class
 * org.springframework.ai.openai.OpenAiChatOptions} from inside {@code
 * OpenAiChatModel.createRequest} - every one of Stage 6a+/6b's automated tests passed because all
 * of them mock {@code ChatModel} entirely, never exercising Spring AI's own internal
 * request-building code. Decompiled every provider's {@code buildRequestPrompt}/{@code
 * createRequest} (`javap`): each one passes a non-{@code null} {@code Prompt.getOptions()} straight
 * through unchanged (only substituting its own default when the prompt's options are {@code null}),
 * then unconditionally casts whatever it finds to its own concrete options type - so attaching a
 * generic, provider-agnostic {@code ChatOptions} (a {@code DefaultChatOptions} instance) to any
 * per-call {@code Prompt} is guaranteed to throw for <b>all four</b> providers (confirmed identical
 * {@code checkcast} pattern in OpenAI, Anthropic, Ollama, and Google GenAI's compiled bytecode),
 * not just OpenAI.
 *
 * <p>Grooming #15's original rationale for the generic approach - avoiding "every provider's own
 * enum-typed {@code model()} setter (e.g. Anthropic's {@code com.anthropic.models.messages.Model},
 * Ollama's {@code OllamaModel})" - turned out to be based on an incomplete reading of the API
 * surface, also confirmed empirically rather than re-guessed: every one of the four concrete
 * builders (verified via a real compiled-and-run probe class against this project's actual
 * classpath, not just `javap`) also inherits a {@code model(String)} overload accepting a plain
 * string - Anthropic/Ollama/Google GenAI's own enum-typed overload sits alongside it, not instead
 * of it. This is what makes attaching a concrete options type per-call both necessary (to avoid the
 * cast) and sufficient (arbitrary model names, including Ollama's user-pulled ones outside its
 * fixed enum, still work through the inherited string overload).
 *
 * <p>OpenAI/Anthropic/Google GenAI clients are built with an explicit {@link #providerTimeout} -
 * found live (audit session, 2026-07-17, decompiled via {@code javap} before relying on it, not
 * guessed) that OpenAI's and Anthropic's Java SDKs both default to a **10-minute** request timeout
 * (`com.{openai,anthropic}.core.Timeout`'s getter fallbacks), and Google GenAI's underlying {@code
 * OkHttpClient} defaults connect/read/write timeouts to `Duration.ofMillis(0)` - **no timeout at
 * all** - unless {@code HttpOptions.timeout()} is explicitly set. Since a construction/probe call
 * (e.g. Stage 3's `EmbeddingModelFactory` dimension probe) holds a Tomcat worker thread for the
 * entire call, a slow/unresponsive provider could otherwise hang a request for up to 10 minutes
 * (OpenAI/Anthropic) or genuinely forever (Google GenAI). Ollama has no equivalent builder-exposed
 * timeout setter (checked via `javap`) and is deliberately left as-is - lower risk as a
 * self-hosted, typically low-latency dependency rather than a cloud call across the open internet.
 *
 * <p>{@code final} (SpotBugs static-analysis pass, `plan.md` §9 2026-07-17, {@code
 * CT_CONSTRUCTOR_THROW}): the constructor can throw ({@link #requirePositiveTimeout}), and a
 * throwing constructor on a subclassable class is a documented finalizer-attack vector (SEI CERT
 * OBJ-11-J) - a malicious subclass could override {@code finalize()} and observe the
 * partially-constructed instance if construction fails partway through. Sealing off subclassing
 * removes the precondition entirely. Confirmed empirically, not assumed, that this doesn't break
 * {@code @MockitoBean} (used on this exact class in {@code KnowledgeBaseIntegrationTest} via {@link
 * com.synapsemcp.embedding.EmbeddingModelFactory}) - Spring Boot's test infrastructure configures
 * Mockito's inline mock maker, which mocks {@code final} classes directly rather than subclassing
 * them.
 */
@Component
public final class ChatModelFactory {

    private final ModelConfigRepository modelConfigRepository;
    private final Duration providerTimeout;
    private final Map<UUID, ChatModel> cache = new ConcurrentHashMap<>();

    public ChatModelFactory(
            ModelConfigRepository modelConfigRepository,
            @Value("${synapsemcp.provider.timeout-seconds:30}") long providerTimeoutSeconds) {
        this.modelConfigRepository = modelConfigRepository;
        this.providerTimeout = requirePositiveTimeout(providerTimeoutSeconds);
    }

    /**
     * Rejects a zero or negative value at startup rather than silently passing it through - found
     * during a later audit pass (`plan.md` §9, 2026-07-17): OkHttp's well-documented convention is
     * that a {@code 0} timeout means <b>no timeout at all</b>, not "fail instantly." An operator
     * setting {@code SYNAPSEMCP_PROVIDER_TIMEOUT_SECONDS=0} - a natural but wrong assumption -
     * would otherwise silently disable this class's entire timeout fix, reverting to the original
     * unbounded hang this fix exists to prevent, with no indication anything was misconfigured.
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

    public ChatModel getChatModel(UUID tenantId) {
        return cache.computeIfAbsent(tenantId, id -> buildChatModel(resolve(id)));
    }

    public ChatOptions optionsFor(UUID tenantId) {
        ModelConfig modelConfig = resolve(tenantId);
        return buildOptions(modelConfig.getChatProvider(), modelConfig.getChatModel());
    }

    /**
     * rag_plan.md Stage 6a+/6b: reranking and {@code ask} both need the chat model resolved from
     * the knowledge_base's <b>locked</b> {@code knowledge_base_model_configs} snapshot (Grooming
     * #23), not the tenant's live, possibly-since-changed {@code model_configs} row - same
     * reasoning {@link
     * com.synapsemcp.embedding.EmbeddingModelFactory#getEmbeddingModelForKnowledgeBase} already
     * established for embeddings. Deliberately <b>not cached</b>, unlike {@link
     * #getChatModel(UUID)}: unlike the tenant-scoped path (a hot, repeated lookup across many
     * requests), a per-search/per-ask rebuild is negligible next to the chat completion call
     * itself, and skipping a cache sidesteps needing a second cache-eviction listener for the
     * credential-sync writes {@code ModelConfigService.syncCredentialsToKnowledgeBaseConfigs} makes
     * (no {@code KnowledgeBaseModelConfigUpdatedEvent} exists for that path).
     */
    public ChatModel getChatModelForKnowledgeBase(KnowledgeBaseModelConfig kbConfig) {
        return buildChatModel(kbConfig.getChatProvider(), kbConfig.getProviderCredentials());
    }

    public ChatOptions optionsForKnowledgeBase(KnowledgeBaseModelConfig kbConfig) {
        return buildOptions(kbConfig.getChatProvider(), kbConfig.getChatModel());
    }

    /**
     * A concrete, provider-specific {@code ChatOptions} - see this class's Javadoc for why the
     * generic {@link ChatOptions#builder()} is unsafe here. Every branch uses that provider's own
     * inherited {@code model(String)} overload (confirmed empirically to exist on all four,
     * alongside - not instead of - each provider's own enum-typed overload), so an arbitrary model
     * name (including Ollama's user-pulled ones) still works.
     */
    @SuppressFBWarnings(
            value = "BC_UNCONFIRMED_CAST_OF_RETURN_VALUE",
            justification =
                    "Each provider's *ChatOptions.Builder self-references its own generic type"
                            + " parameter (<B extends AbstractBuilder<B>>), so the inherited"
                            + " model(String) overload's return type can't be statically confirmed"
                            + " by SpotBugs across that generic boundary - verified safe by both"
                            + " compiling and running a standalone probe against this exact"
                            + " construct for all four providers, and by a real end-to-end smoke"
                            + " test against genuine OpenAI/Google GenAI credentials exercising"
                            + " this exact method (`plan.md` §9 2026-07-18).")
    private static ChatOptions buildOptions(String chatProvider, String chatModelName) {
        return switch (chatProvider) {
            case "openai" -> OpenAiChatOptions.builder().model(chatModelName).build();
            case "anthropic" -> AnthropicChatOptions.builder().model(chatModelName).build();
            case "ollama" -> OllamaChatOptions.builder().model(chatModelName).build();
            case "google-genai" -> GoogleGenAiChatOptions.builder().model(chatModelName).build();
            default ->
                    throw new IllegalStateException("unsupported chat provider: " + chatProvider);
        };
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
        return buildChatModel(modelConfig.getChatProvider(), modelConfig.getProviderCredentials());
    }

    /**
     * Shared by both the tenant-scoped, cached path ({@link #buildChatModel(ModelConfig)}) and the
     * knowledge_base-scoped, uncached path ({@link #getChatModelForKnowledgeBase}) - one copy of
     * the provider-construction switch, since {@link ModelConfig} and {@link
     * KnowledgeBaseModelConfig} share the same provider/credentials shape but aren't otherwise
     * related types (same pattern as {@code EmbeddingModelFactory}'s own shared private overload).
     */
    private ChatModel buildChatModel(String chatProvider, String encodedCredentials) {
        ProviderCredentials credentials = ProviderCredentialsCodec.decode(encodedCredentials);
        return switch (chatProvider) {
            case "openai" ->
                    OpenAiChatModel.builder()
                            .options(
                                    OpenAiChatOptions.builder()
                                            .apiKey(credentials.chatApiKey())
                                            .build())
                            .httpClientBuilderCustomizer(
                                    builder -> builder.timeout(providerTimeout))
                            .build();
            case "anthropic" ->
                    AnthropicChatModel.builder()
                            .options(
                                    AnthropicChatOptions.builder()
                                            .apiKey(credentials.chatApiKey())
                                            .build())
                            .httpClientBuilderCustomizer(
                                    builder -> builder.timeout(providerTimeout))
                            .build();
            case "ollama" ->
                    OllamaChatModel.builder().ollamaApi(OllamaApi.builder().build()).build();
            case "google-genai" ->
                    GoogleGenAiChatModel.builder()
                            .genAiClient(
                                    Client.builder()
                                            .apiKey(credentials.chatApiKey())
                                            .httpOptions(
                                                    HttpOptions.builder()
                                                            .timeout(
                                                                    (int)
                                                                            providerTimeout
                                                                                    .toMillis())
                                                            .build())
                                            .build())
                            .build();
            default ->
                    throw new IllegalStateException("unsupported chat provider: " + chatProvider);
        };
    }
}
