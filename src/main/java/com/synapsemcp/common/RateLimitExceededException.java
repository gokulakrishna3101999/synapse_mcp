package com.synapsemcp.common;

import org.springframework.http.HttpStatus;

/**
 * mcp_plan.md Stage 3: thrown by {@code RateLimitAspect} when a tenant+provider+kind bucket is
 * exhausted. Extends {@link ApiException} rather than introducing a parallel exception hierarchy -
 * REST gets a dedicated {@code @ExceptionHandler} adding a {@code Retry-After} header (mirroring
 * {@code ApiExceptionHandler#handleLockTimeout}'s existing precedent), and MCP already handles any
 * {@code ApiException} cleanly today (its {@code getMessage()} is client-safe, confirmed Grooming
 * #18/#19) - no MCP-specific handling needed.
 */
public class RateLimitExceededException extends ApiException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
