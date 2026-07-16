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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.http.HttpStatus;

/**
 * No live provider credentials are available in this environment (rag_plan.md Stage 0 blocker), so
 * these tests can only verify that each provider's builder wires up without throwing at
 * *construction* time - never that an actual API call succeeds.
 */
class ChatModelFactoryTest {

    private final ModelConfigRepository modelConfigRepository = mock(ModelConfigRepository.class);
    private final ChatModelFactory factory = new ChatModelFactory(modelConfigRepository);
    private final UUID tenantId = UUID.randomUUID();

    private static Stream<String> chatProviders() {
        return Stream.of("openai", "anthropic", "ollama", "google-genai");
    }

    private void stubModelConfig(String chatProvider) {
        Tenant tenant = mock(Tenant.class);
        ModelConfig modelConfig =
                new ModelConfig(
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

    @Test
    void optionsForAttachesTheTenantsConfiguredModelNameGenerically() {
        stubModelConfig("openai");

        ChatOptions options = factory.optionsFor(tenantId);

        assertThat(options.getModel()).isEqualTo("some-model");
    }

    @Test
    void optionsForThrows422WhenTenantHasNoModelConfigRow() {
        when(modelConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> factory.optionsFor(tenantId)).isInstanceOf(ApiException.class);
    }
}
