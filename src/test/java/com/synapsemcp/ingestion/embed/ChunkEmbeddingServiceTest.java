package com.synapsemcp.ingestion.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.RedisKeyPrefix;
import com.synapsemcp.embedding.EmbeddingModelFactory;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ChunkEmbeddingServiceTest {

    private final EmbeddingModelFactory embeddingModelFactory = mock(EmbeddingModelFactory.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private final RedisKeyPrefix redisKeyPrefix = new RedisKeyPrefix("test");
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

    private ChunkEmbeddingService service;
    private KnowledgeBaseModelConfig kbConfig;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(embeddingModelFactory.getEmbeddingModelForKnowledgeBase(any()))
                .thenReturn(embeddingModel);
        service = new ChunkEmbeddingService(embeddingModelFactory, redisTemplate, redisKeyPrefix);
        kbConfig =
                KnowledgeBaseModelConfig.create(
                        null, "openai", "gpt-4o", "openai", "text-embedding-3-small", "creds");
    }

    @Test
    void returnsEmptyListForEmptyInputWithoutTouchingTheEmbeddingModel() {
        List<float[]> result = service.embed(kbConfig, List.of());

        assertThat(result).isEmpty();
        verify(embeddingModelFactory, never()).getEmbeddingModelForKnowledgeBase(any());
    }

    @Test
    void embedsAllTextsOnACompleteCacheMissAndWritesBackWithTtl() {
        when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(null, null));
        when(embeddingModel.embed(anyList()))
                .thenReturn(List.of(new float[] {1f, 2f}, new float[] {3f, 4f}));

        List<float[]> result = service.embed(kbConfig, List.of("first text", "second text"));

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsExactly(1f, 2f);
        assertThat(result.get(1)).containsExactly(3f, 4f);
        verify(embeddingModel).embed(List.of("first text", "second text"));
        verify(valueOperations, times(2)).set(anyString(), anyString(), eq(Duration.ofDays(7)));
    }

    @Test
    void skipsTheEmbeddingModelEntirelyOnACompleteCacheHit() {
        when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList("1.0,2.0", "3.0,4.0"));

        List<float[]> result = service.embed(kbConfig, List.of("cached one", "cached two"));

        assertThat(result.get(0)).containsExactly(1.0f, 2.0f);
        assertThat(result.get(1)).containsExactly(3.0f, 4.0f);
        verify(embeddingModelFactory, never()).getEmbeddingModelForKnowledgeBase(any());
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void onlyEmbedsCacheMissesAndPreservesOriginalOrdering() {
        when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList("9.0,9.0", null));
        when(embeddingModel.embed(List.of("miss text"))).thenReturn(List.of(new float[] {5f, 6f}));

        List<float[]> result = service.embed(kbConfig, List.of("hit text", "miss text"));

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsExactly(9.0f, 9.0f);
        assertThat(result.get(1)).containsExactly(5f, 6f);
        verify(embeddingModel).embed(List.of("miss text"));
        verify(valueOperations, times(1)).set(anyString(), anyString(), eq(Duration.ofDays(7)));
    }
}
