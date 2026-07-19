package com.synapsemcp.ingestion.embed;

import com.synapsemcp.common.RedisKeyPrefix;
import com.synapsemcp.embedding.EmbeddingModelFactory;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * rag_plan.md Stage 5c: batches every cache-miss into one embedding call, resolving the model from
 * the knowledge_base's locked snapshot (Grooming #23), not the tenant's live config.
 *
 * <p>Cache key is {@code sha256(provider:model:normalized_text)}, TTL 7 days (rag_plan.md's own
 * words). "Normalized" is underspecified in the plan beyond that phrase - trim + collapse internal
 * whitespace runs to a single space is used here, a defensible, low-stakes reading: unlike a wrong
 * embedding *value*, an imperfect normalization only costs cache hit rate (a near-duplicate text
 * recomputes instead of reusing a cached vector), never correctness, so this wasn't escalated as a
 * design question.
 *
 * <p>Redis reads/writes are fail-open (Grooming #68, found live, `plan.md` §9 2026-07-17): this
 * project has an established, explicitly-documented principle that Redis is never allowed to gate
 * otherwise-working functionality ({@code TenantCreationRateLimitFilter}'s own Javadoc; {@code
 * management.health.redis.enabled: false}). A real {@code CLIENT PAUSE}-simulated Redis outage
 * confirmed this cache's calls were unguarded and failed the entire ingestion job on a Redis
 * timeout - purely a performance optimization taking down otherwise-good ingestion. Both the
 * cache-read and cache-write calls now catch and log rather than propagate; a Redis failure simply
 * degrades to "treat as a cache miss" / "skip caching this result", never fails the job.
 */
@Service
public class ChunkEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkEmbeddingService.class);
    private static final Duration CACHE_TTL = Duration.ofDays(7);

    private final EmbeddingModelFactory embeddingModelFactory;
    private final StringRedisTemplate redisTemplate;
    private final RedisKeyPrefix redisKeyPrefix;

    ChunkEmbeddingService(
            EmbeddingModelFactory embeddingModelFactory,
            StringRedisTemplate redisTemplate,
            RedisKeyPrefix redisKeyPrefix) {
        this.embeddingModelFactory = embeddingModelFactory;
        this.redisTemplate = redisTemplate;
        this.redisKeyPrefix = redisKeyPrefix;
    }

    /**
     * @return embeddings in the same order as {@code texts}.
     */
    public List<float[]> embed(KnowledgeBaseModelConfig kbConfig, List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }

        List<String> cacheKeys =
                texts.stream()
                        .map(
                                text ->
                                        cacheKey(
                                                kbConfig.getEmbeddingProvider(),
                                                kbConfig.getEmbeddingModel(),
                                                text))
                        .toList();
        List<String> cachedValues = safeMultiGet(cacheKeys);

        float[][] results = new float[texts.size()][];
        List<Integer> missIndices = new ArrayList<>();
        List<String> missTexts = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String cached = cachedValues == null ? null : cachedValues.get(i);
            if (cached != null) {
                results[i] = deserialize(cached);
            } else {
                missIndices.add(i);
                missTexts.add(texts.get(i));
            }
        }

        if (!missTexts.isEmpty()) {
            EmbeddingModel embeddingModel =
                    embeddingModelFactory.getEmbeddingModelForKnowledgeBase(kbConfig);
            List<float[]> embedded = embeddingModel.embed(missTexts);
            for (int j = 0; j < missIndices.size(); j++) {
                int index = missIndices.get(j);
                float[] vector = embedded.get(j);
                results[index] = vector;
                safeCacheWrite(cacheKeys.get(index), serialize(vector));
            }
        }

        return Arrays.asList(results);
    }

    private List<String> safeMultiGet(List<String> cacheKeys) {
        try {
            return redisTemplate.opsForValue().multiGet(cacheKeys);
        } catch (Exception e) {
            log.warn("embedding cache read failed - falling back to direct embedding", e);
            return null;
        }
    }

    private void safeCacheWrite(String key, String value) {
        try {
            redisTemplate.opsForValue().set(key, value, CACHE_TTL);
        } catch (Exception e) {
            log.warn("embedding cache write failed - continuing without caching this entry", e);
        }
    }

    private String cacheKey(String provider, String model, String text) {
        String normalized = text.strip().replaceAll("\\s+", " ");
        String raw = provider + ":" + model + ":" + normalized;
        return redisKeyPrefix.key("embedding-cache:" + sha256Hex(raw));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String serialize(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12);
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.toString();
    }

    private static float[] deserialize(String value) {
        String[] parts = value.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i]);
        }
        return vector;
    }
}
