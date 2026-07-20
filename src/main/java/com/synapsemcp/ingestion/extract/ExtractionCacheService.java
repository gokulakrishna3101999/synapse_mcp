package com.synapsemcp.ingestion.extract;

import com.synapsemcp.common.RedisKeyPrefix;
import com.synapsemcp.document.ContentHasher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * rag_plan.md Stage 5a: the 24h Redis cache shared by {@link PdfExtractor} and {@link
 * ImageExtractor} - both are LLM-vision-first and this cache is what makes re-extracting the same
 * file (e.g. a Grooming #26 {@code FAILED}-job retry-via-reupload) not repeat a real, billed vision
 * call. Keyed by content hash alone (not provider/model, unlike {@code ChunkEmbeddingService}'s
 * embedding cache) - re-extracting the identical bytes should reuse whatever text was already
 * successfully transcribed from them, regardless of which provider happened to be configured at the
 * time, since the plan states this cache exists "by content hash", not "by content hash + model".
 *
 * <p>Fail-open on both read and write (same established convention as {@code
 * ChunkEmbeddingService}'s embedding cache, Grooming #68) - a Redis outage must degrade to "treat
 * as a cache miss" / "skip caching this result", never fail the ingestion job over a pure
 * performance optimization.
 */
@Component
public class ExtractionCacheService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionCacheService.class);
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final String CACHE_METRIC = "synapsemcp.extraction.cache";

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyPrefix redisKeyPrefix;
    private final MeterRegistry meterRegistry;

    ExtractionCacheService(
            StringRedisTemplate redisTemplate,
            RedisKeyPrefix redisKeyPrefix,
            MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.redisKeyPrefix = redisKeyPrefix;
        this.meterRegistry = meterRegistry;
    }

    /**
     * @param cacheNamespace a short, stable label distinguishing this cache from others sharing the
     *     same Redis database (e.g. {@code "pdf-extract"}, {@code "image-extract"}).
     */
    public String get(String cacheNamespace, byte[] content) {
        String value;
        try {
            value = redisTemplate.opsForValue().get(key(cacheNamespace, content));
        } catch (Exception e) {
            log.warn("{} cache read failed - treating as a cache miss", cacheNamespace, e);
            value = null;
        }
        recordCacheResult(cacheNamespace, value != null ? "hit" : "miss");
        return value;
    }

    private void recordCacheResult(String cacheNamespace, String result) {
        Counter.builder(CACHE_METRIC)
                .tag("cache", cacheNamespace)
                .tag("result", result)
                .description("OCR/vision extraction cache hit/miss count")
                .register(meterRegistry)
                .increment();
    }

    public void put(String cacheNamespace, byte[] content, String text) {
        try {
            redisTemplate.opsForValue().set(key(cacheNamespace, content), text, CACHE_TTL);
        } catch (Exception e) {
            log.warn(
                    "{} cache write failed - continuing without caching this entry",
                    cacheNamespace,
                    e);
        }
    }

    private String key(String cacheNamespace, byte[] content) {
        return redisKeyPrefix.key(cacheNamespace + ":" + ContentHasher.sha256Hex(content));
    }
}
