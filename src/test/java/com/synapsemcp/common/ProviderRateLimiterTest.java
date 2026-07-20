package com.synapsemcp.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProviderRateLimiterTest {

    @SuppressWarnings("unchecked")
    private final ProxyManager<byte[]> proxyManager = mock(ProxyManager.class);

    private final RedisKeyPrefix redisKeyPrefix = new RedisKeyPrefix("test");
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ProviderRateLimiter limiter =
            new ProviderRateLimiter(proxyManager, redisKeyPrefix, meterRegistry);

    @Test
    void allowsARequestUnderTheRedisBackedLimit() {
        BucketProxy bucketProxy = mock(BucketProxy.class);
        when(bucketProxy.tryConsumeAndReturnRemaining(1))
                .thenReturn(ConsumptionProbe.consumed(59, Duration.ofSeconds(1).toNanos()));
        when(proxyManager.getProxy(any(), any())).thenReturn(bucketProxy);

        RateLimitResult result =
                limiter.tryConsume(UUID.randomUUID(), "openai", RateLimitKind.CHAT);

        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(59);
    }

    @Test
    void deniesARequestOverTheRedisBackedLimit() {
        BucketProxy bucketProxy = mock(BucketProxy.class);
        when(bucketProxy.tryConsumeAndReturnRemaining(1))
                .thenReturn(
                        ConsumptionProbe.rejected(
                                0,
                                Duration.ofSeconds(30).toNanos(),
                                Duration.ofSeconds(30).toNanos()));
        when(proxyManager.getProxy(any(), any())).thenReturn(bucketProxy);

        RateLimitResult result =
                limiter.tryConsume(UUID.randomUUID(), "openai", RateLimitKind.CHAT);

        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfterSeconds()).isGreaterThan(0);
    }

    /** mcp_plan.md Cross-Cutting Concerns, Grooming #10: Bucket4j rate-limit rejection metric. */
    @Test
    void recordsARejectionCounterOnDenialButNotOnSuccess() {
        BucketProxy bucketProxy = mock(BucketProxy.class);
        when(bucketProxy.tryConsumeAndReturnRemaining(1))
                .thenReturn(ConsumptionProbe.consumed(59, 0))
                .thenReturn(ConsumptionProbe.rejected(0, Duration.ofSeconds(30).toNanos(), 0));
        when(proxyManager.getProxy(any(), any())).thenReturn(bucketProxy);

        limiter.tryConsume(UUID.randomUUID(), "openai", RateLimitKind.CHAT);
        assertThat(
                        meterRegistry
                                .find("synapsemcp.rate_limit.rejections")
                                .tag("provider", "openai")
                                .tag("kind", "CHAT")
                                .counter())
                .isNull();

        limiter.tryConsume(UUID.randomUUID(), "openai", RateLimitKind.CHAT);
        assertThat(
                        meterRegistry
                                .counter(
                                        "synapsemcp.rate_limit.rejections",
                                        "provider",
                                        "openai",
                                        "kind",
                                        "CHAT")
                                .count())
                .isEqualTo(1.0);
    }

    /**
     * mcp_plan.md Stage 3 - deliberately breaks this codebase's Redis-fail-open convention
     * (Grooming #20): on a Redis failure, falls back to a much stricter local bucket rather than
     * failing open.
     */
    @Test
    void fallsBackToAConservativeLocalLimitWhenRedisIsUnreachable() {
        when(proxyManager.getProxy(any(), any())).thenThrow(new RuntimeException("redis down"));

        UUID tenantId = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            RateLimitResult result = limiter.tryConsume(tenantId, "openai", RateLimitKind.CHAT);
            assertThat(result.allowed()).as("request %d of 10 should be allowed", i + 1).isTrue();
        }

        RateLimitResult eleventh = limiter.tryConsume(tenantId, "openai", RateLimitKind.CHAT);

        assertThat(eleventh.allowed())
                .as(
                        "local fallback is capped at 10/minute, stricter than the 60/minute Redis limit")
                .isFalse();
    }

    @Test
    void localFallbackBucketsAreIndependentPerTenantProviderAndKind() {
        when(proxyManager.getProxy(any(), any())).thenThrow(new RuntimeException("redis down"));
        UUID tenantId = UUID.randomUUID();

        for (int i = 0; i < 10; i++) {
            limiter.tryConsume(tenantId, "openai", RateLimitKind.CHAT);
        }
        RateLimitResult sameTenantDifferentProvider =
                limiter.tryConsume(tenantId, "anthropic", RateLimitKind.CHAT);
        RateLimitResult sameTenantDifferentKind =
                limiter.tryConsume(tenantId, "openai", RateLimitKind.EMBEDDING);
        RateLimitResult differentTenant =
                limiter.tryConsume(UUID.randomUUID(), "openai", RateLimitKind.CHAT);

        assertThat(sameTenantDifferentProvider.allowed()).isTrue();
        assertThat(sameTenantDifferentKind.allowed()).isTrue();
        assertThat(differentTenant.allowed()).isTrue();
    }
}
