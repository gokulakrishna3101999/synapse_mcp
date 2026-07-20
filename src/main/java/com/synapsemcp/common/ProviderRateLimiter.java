package com.synapsemcp.common;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * mcp_plan.md Stage 3: per-tenant, per-provider, per-{@link RateLimitKind} token bucket, backed by
 * Redis (shared by REST and MCP traffic alike, Grooming #5) so both consume from the same budget.
 *
 * <p><b>Deliberately breaks this codebase's otherwise-universal Redis-fail-open convention</b>
 * (Grooming #68, {@code FixedWindowRateLimiter}, the embedding/OCR caches) - user confirmed
 * (Grooming #20): on a Redis failure, falls back to a much stricter <i>local, non-distributed</i>
 * bucket (10/minute vs. the Redis-backed 60/minute) rather than failing open. Every existing
 * fail-open component in this codebase guards either a pure performance optimization (a cache) or
 * an open, unauthenticated endpoint with no other abuse protection - failing open there only costs
 * latency or a temporary unlimited-request window. This limiter instead guards paid, metered
 * third-party provider calls on already-authenticated, tenant-scoped traffic - failing open here
 * would remove the only guard against runaway provider cost during an outage, a materially
 * different risk. The local fallback is intentionally per-JVM-instance, not shared (this app is not
 * currently deployed across multiple instances - Phase 3 territory) - it exists to keep {@code
 * ask}/{@code search}/{@code ingest} alive through a Redis blip at a conservative cap, not to
 * replace the real distributed limit.
 */
@Component
public class ProviderRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ProviderRateLimiter.class);
    private static final String REJECTIONS_METRIC = "synapsemcp.rate_limit.rejections";

    private static final long REDIS_CAPACITY = 60;
    private static final Duration REDIS_PERIOD = Duration.ofMinutes(1);
    private static final long LOCAL_FALLBACK_CAPACITY = 10;
    private static final Duration LOCAL_FALLBACK_PERIOD = Duration.ofMinutes(1);

    private final ProxyManager<byte[]> proxyManager;
    private final RedisKeyPrefix redisKeyPrefix;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Bucket> localFallbackBuckets =
            new ConcurrentHashMap<>();

    ProviderRateLimiter(
            ProxyManager<byte[]> proxyManager,
            RedisKeyPrefix redisKeyPrefix,
            MeterRegistry meterRegistry) {
        this.proxyManager = proxyManager;
        this.redisKeyPrefix = redisKeyPrefix;
        this.meterRegistry = meterRegistry;
    }

    public RateLimitResult tryConsume(UUID tenantId, String provider, RateLimitKind kind) {
        String key =
                redisKeyPrefix.key("rate_limit:provider:" + tenantId + ":" + provider + ":" + kind);
        RateLimitResult result;
        try {
            result = toResult(consumeFromRedis(key));
        } catch (Exception e) {
            log.warn(
                    "Rate limiter Redis backend unreachable for key {}, falling back to a"
                            + " conservative local limit",
                    key,
                    e);
            result = toResult(consumeLocally(key));
        }
        if (!result.allowed()) {
            recordRejection(provider, kind);
        }
        return result;
    }

    /** mcp_plan.md Cross-Cutting Concerns, Grooming #10: Bucket4j rate-limit rejection metric. */
    private void recordRejection(String provider, RateLimitKind kind) {
        Counter.builder(REJECTIONS_METRIC)
                .tag("provider", provider)
                .tag("kind", kind.name())
                .description("Cumulative count of requests denied by the provider rate limiter")
                .register(meterRegistry)
                .increment();
    }

    private ConsumptionProbe consumeFromRedis(String key) {
        BucketConfiguration config =
                BucketConfiguration.builder()
                        .addLimit(Bandwidth.simple(REDIS_CAPACITY, REDIS_PERIOD))
                        .build();
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        return proxyManager.getProxy(keyBytes, () -> config).tryConsumeAndReturnRemaining(1);
    }

    private ConsumptionProbe consumeLocally(String key) {
        Bucket bucket =
                localFallbackBuckets.computeIfAbsent(
                        key,
                        k ->
                                Bucket.builder()
                                        .addLimit(
                                                Bandwidth.simple(
                                                        LOCAL_FALLBACK_CAPACITY,
                                                        LOCAL_FALLBACK_PERIOD))
                                        .build());
        return bucket.tryConsumeAndReturnRemaining(1);
    }

    private static RateLimitResult toResult(ConsumptionProbe probe) {
        long retryAfterSeconds = Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1;
        return new RateLimitResult(
                probe.isConsumed(), probe.getRemainingTokens(), retryAfterSeconds);
    }
}
