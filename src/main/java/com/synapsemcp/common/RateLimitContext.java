package com.synapsemcp.common;

/**
 * Request-scoped holder for the last-observed remaining-token count from {@code
 * ProviderRateLimiter} (mcp_plan.md Stage 3) - same shape as {@link TenantContext}. {@code
 * RateLimitAspect} sets this after a successful {@code tryConsume}; {@code RateLimitHeaderFilter}
 * reads it once the REST request finishes to set {@code X-RateLimit-Remaining}. MCP tool calls have
 * no header concept, so this is never read on that path - MCP is intentionally scoped to structured
 * errors on limit breach only, not a running remaining-count on every successful call.
 */
public final class RateLimitContext {

    private static final ThreadLocal<Long> REMAINING_TOKENS = new ThreadLocal<>();

    private RateLimitContext() {}

    public static void set(long remainingTokens) {
        REMAINING_TOKENS.set(remainingTokens);
    }

    public static Long get() {
        return REMAINING_TOKENS.get();
    }

    public static void clear() {
        REMAINING_TOKENS.remove();
    }
}
