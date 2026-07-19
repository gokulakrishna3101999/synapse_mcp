package com.synapsemcp.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.ProviderCredentials;
import com.synapsemcp.common.ProviderCredentialsCodec;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import com.synapsemcp.tenant.ModelConfig;
import com.synapsemcp.tenant.ModelConfigRepository;
import com.synapsemcp.tenant.ModelConfigUpdatedEvent;
import com.synapsemcp.tenant.Tenant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.HttpStatus;

/**
 * No live provider credentials are available in this environment (rag_plan.md Stage 0 blocker), so
 * most of these tests can only verify that each provider's builder wires up without throwing at
 * *construction* time - never that an actual API call succeeds. The {@code optionsFor}/{@code
 * optionsForKnowledgeBase} concrete-type assertions below are the exception - found live (`plan.md`
 * §9 2026-07-18) via a genuine end-to-end smoke test against real OpenAI credentials that the
 * previous generic-{@link ChatOptions} implementation threw {@code ClassCastException} from inside
 * every provider's own real request-building code (confirmed identical for all four via
 * decompilation) - these tests assert the fixed, provider-specific return type directly, which is
 * exactly the invariant that makes the cast safe without needing a real network call to re-prove it
 * per provider.
 */
class ChatModelFactoryTest {

    private final ModelConfigRepository modelConfigRepository = mock(ModelConfigRepository.class);
    private final ChatModelFactory factory = new ChatModelFactory(modelConfigRepository, 30);
    private final UUID tenantId = UUID.randomUUID();

    private static Stream<String> chatProviders() {
        return Stream.of("openai", "anthropic", "ollama", "google-genai");
    }

    /**
     * Found during a later audit pass: OkHttp treats a {@code 0} timeout as "no timeout at all,"
     * not "fail instantly" - a natural but wrong operator assumption that would otherwise silently
     * disable this class's entire timeout fix. Rejected at construction instead.
     */
    @Test
    void rejectsAZeroOrNegativeTimeoutAtConstruction() {
        assertThatThrownBy(() -> new ChatModelFactory(modelConfigRepository, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatModelFactory(modelConfigRepository, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void stubModelConfig(String chatProvider) {
        Tenant tenant = mock(Tenant.class);
        ModelConfig modelConfig =
                ModelConfig.create(
                        tenant,
                        chatProvider,
                        "some-model",
                        "openai",
                        "text-embedding-3-small",
                        ProviderCredentialsCodec.encode(
                                new ProviderCredentials("fake-key", "fake-key")));
        when(modelConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(modelConfig));
    }

    @ParameterizedTest
    @MethodSource("chatProviders")
    void buildsAChatModelForEverySupportedProviderWithoutThrowing(String provider) {
        stubModelConfig(provider);

        ChatModel chatModel = factory.getChatModel(tenantId);

        assertThat(chatModel).isNotNull();
    }

    @Test
    void cachesTheChatModelPerTenantAcrossRepeatedCalls() {
        stubModelConfig("openai");

        ChatModel first = factory.getChatModel(tenantId);
        ChatModel second = factory.getChatModel(tenantId);

        assertThat(first).isSameAs(second);
        verify(modelConfigRepository, times(1)).findByTenantId(tenantId);
    }

    @Test
    void evictsTheCachedChatModelWhenModelConfigUpdatedEventFires() {
        stubModelConfig("openai");
        ChatModel first = factory.getChatModel(tenantId);

        factory.onModelConfigUpdated(new ModelConfigUpdatedEvent(this, tenantId));
        ChatModel second = factory.getChatModel(tenantId);

        assertThat(second).isNotSameAs(first);
        verify(modelConfigRepository, times(2)).findByTenantId(tenantId);
    }

    @Test
    void doesNotEvictOtherTenantsCacheEntries() {
        stubModelConfig("openai");
        ChatModel first = factory.getChatModel(tenantId);

        factory.onModelConfigUpdated(new ModelConfigUpdatedEvent(this, UUID.randomUUID()));
        ChatModel second = factory.getChatModel(tenantId);

        assertThat(second).isSameAs(first);
    }

    @Test
    void throws422WhenTenantHasNoModelConfigRow() {
        when(modelConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> factory.getChatModel(tenantId))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @ParameterizedTest
    @MethodSource("chatProviders")
    void optionsForReturnsTheConcreteProviderSpecificOptionsType(String provider) {
        stubModelConfig(provider);

        ChatOptions options = factory.optionsFor(tenantId);

        assertThat(options.getModel()).isEqualTo("some-model");
        assertThat(options).isInstanceOf(concreteOptionsTypeFor(provider));
    }

    @Test
    void optionsForThrows422WhenTenantHasNoModelConfigRow() {
        when(modelConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> factory.optionsFor(tenantId)).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @MethodSource("chatProviders")
    void buildsAChatModelForKnowledgeBaseForEverySupportedProviderWithoutThrowing(String provider) {
        KnowledgeBaseModelConfig kbConfig = kbConfig(provider, "kb-model");

        ChatModel chatModel = factory.getChatModelForKnowledgeBase(kbConfig);

        assertThat(chatModel).isNotNull();
    }

    @Test
    void getChatModelForKnowledgeBaseNeverConsultsModelConfigRepository() {
        KnowledgeBaseModelConfig kbConfig = kbConfig("openai", "kb-model");

        factory.getChatModelForKnowledgeBase(kbConfig);

        verify(modelConfigRepository, times(0)).findByTenantId(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getChatModelForKnowledgeBaseIsNotCached() {
        KnowledgeBaseModelConfig kbConfig = kbConfig("openai", "kb-model");

        ChatModel first = factory.getChatModelForKnowledgeBase(kbConfig);
        ChatModel second = factory.getChatModelForKnowledgeBase(kbConfig);

        assertThat(first).isNotSameAs(second);
    }

    @ParameterizedTest
    @MethodSource("chatProviders")
    void optionsForKnowledgeBaseReturnsTheConcreteProviderSpecificOptionsType(String provider) {
        KnowledgeBaseModelConfig kbConfig = kbConfig(provider, "kb-locked-model");

        ChatOptions options = factory.optionsForKnowledgeBase(kbConfig);

        assertThat(options.getModel()).isEqualTo("kb-locked-model");
        assertThat(options).isInstanceOf(concreteOptionsTypeFor(provider));
    }

    private static Class<? extends ChatOptions> concreteOptionsTypeFor(String provider) {
        return switch (provider) {
            case "openai" -> OpenAiChatOptions.class;
            case "anthropic" -> AnthropicChatOptions.class;
            case "ollama" -> OllamaChatOptions.class;
            case "google-genai" -> GoogleGenAiChatOptions.class;
            default -> throw new IllegalArgumentException("unexpected provider: " + provider);
        };
    }

    private static KnowledgeBaseModelConfig kbConfig(String chatProvider, String chatModel) {
        return KnowledgeBaseModelConfig.create(
                null,
                chatProvider,
                chatModel,
                "openai",
                "text-embedding-3-small",
                ProviderCredentialsCodec.encode(new ProviderCredentials("fake-key", "fake-key")));
    }
}
