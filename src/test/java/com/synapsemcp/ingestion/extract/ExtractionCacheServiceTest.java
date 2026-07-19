package com.synapsemcp.ingestion.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.RedisKeyPrefix;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ExtractionCacheServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final RedisKeyPrefix redisKeyPrefix = new RedisKeyPrefix("synapsemcp");
    private final ExtractionCacheService cacheService =
            new ExtractionCacheService(redisTemplate, redisKeyPrefix);

    @Test
    void missReturnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);

        assertThat(cacheService.get("pdf-extract", new byte[] {1, 2, 3})).isNull();
    }

    @Test
    void hitReturnsTheCachedValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(
                        "synapsemcp:pdf-extract:"
                                + com.synapsemcp.document.ContentHasher.sha256Hex(
                                        new byte[] {1, 2, 3})))
                .thenReturn("cached text");

        assertThat(cacheService.get("pdf-extract", new byte[] {1, 2, 3})).isEqualTo("cached text");
    }

    @Test
    void putWritesWithA24HourTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        cacheService.put("pdf-extract", new byte[] {1, 2, 3}, "text to cache");

        String expectedKey =
                "synapsemcp:pdf-extract:"
                        + com.synapsemcp.document.ContentHasher.sha256Hex(new byte[] {1, 2, 3});
        org.mockito.Mockito.verify(valueOps)
                .set(expectedKey, "text to cache", Duration.ofHours(24));
    }

    @Test
    void readFailureFailsOpenAsACacheMiss() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertThat(cacheService.get("pdf-extract", new byte[] {1})).isNull();
    }

    @Test
    void writeFailureFailsOpenSilently() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        cacheService.put("pdf-extract", new byte[] {1}, "text");
        // no exception propagated - that's the assertion
    }
}
