package com.synapsemcp.common;

/** Result of {@code ProviderRateLimiter.tryConsume} (mcp_plan.md Stage 3). */
public record RateLimitResult(boolean allowed, long remainingTokens, long retryAfterSeconds) {}
