package com.synapsemcp.embedding;

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
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.http.HttpStatus;

/**
 * No live provider credentials are available in this environment (rag_plan.md Stage 0 blocker), so
 * these tests can only verify that each provider's builder wires up without throwing at
 * *construction* time - never that an actual embed() call succeeds.
 */
class EmbeddingModelFactoryTest {

    private final ModelConfigRepository modelConfigRepository = mock(ModelConfigRepository.class);
    private final EmbeddingModelFactory factory =
            new EmbeddingModelFactory(modelConfigRepository, 30);
    private final UUID tenantId = UUID.randomUUID();

    private static Stream<String> embeddingProviders() {
        return Stream.of("openai", "ollama", "google-genai");
    }

    /**
     * Found during a later audit pass: OkHttp treats a {@code 0} timeout as "no timeout at all,"
     * not "fail instantly" - a natural but wrong operator assumption that would otherwise silently
     * disable this class's entire timeout fix. Rejected at construction instead.
     */
    @Test
    void rejectsAZeroOrNegativeTimeoutAtConstruction() {
        assertThatThrownBy(() -> new EmbeddingModelFactory(modelConfigRepository, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingModelFactory(modelConfigRepository, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void stubModelConfig(String embeddingProvider) {
        Tenant tenant = mock(Tenant.class);
        ModelConfig modelConfig =
                ModelConfig.create(
                        tenant,
                        "openai",
                        "gpt-4o",
                        embeddingProvider,
                        "some-embedding-model",
                        ProviderCredentialsCodec.encode(
                                new ProviderCredentials("fake-key", "fake-key")));
        when(modelConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(modelConfig));
    }

    @ParameterizedTest
    @MethodSource("embeddingProviders")
    void buildsAnEmbeddingModelForEverySupportedProviderWithoutThrowing(String provider) {
        stubModelConfig(provider);

        EmbeddingModel embeddingModel = factory.getEmbeddingModel(tenantId);

        assertThat(embeddingModel).isNotNull();
    }

    @Test
    void cachesTheEmbeddingModelPerTenantAcrossRepeatedCalls() {
        stubModelConfig("openai");

        EmbeddingModel first = factory.getEmbeddingModel(tenantId);
        EmbeddingModel second = factory.getEmbeddingModel(tenantId);

        assertThat(first).isSameAs(second);
        verify(modelConfigRepository, times(1)).findByTenantId(tenantId);
    }

    @Test
    void evictsTheCachedEmbeddingModelWhenModelConfigUpdatedEventFires() {
        stubModelConfig("openai");
        EmbeddingModel first = factory.getEmbeddingModel(tenantId);

        factory.onModelConfigUpdated(new ModelConfigUpdatedEvent(this, tenantId));
        EmbeddingModel second = factory.getEmbeddingModel(tenantId);

        assertThat(second).isNotSameAs(first);
        verify(modelConfigRepository, times(2)).findByTenantId(tenantId);
    }

    @Test
    void doesNotEvictOtherTenantsCacheEntries() {
        stubModelConfig("openai");
        EmbeddingModel first = factory.getEmbeddingModel(tenantId);

        factory.onModelConfigUpdated(new ModelConfigUpdatedEvent(this, UUID.randomUUID()));
        EmbeddingModel second = factory.getEmbeddingModel(tenantId);

        assertThat(second).isSameAs(first);
    }

    @Test
    void throws422WhenTenantHasNoModelConfigRow() {
        when(modelConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> factory.getEmbeddingModel(tenantId))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }
}
