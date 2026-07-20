package com.synapsemcp.tenant;

import com.synapsemcp.common.FixedWindowRateLimiter;
import com.synapsemcp.common.RedisKeyPrefix;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-IP fixed-window limiter on {@code POST /api/v1/tenants} (rag_plan.md Stage 1, Grooming #22):
 * the endpoint is open/unauthenticated by design (no admin tier - `plan.md` §9), so this is the
 * only abuse guard against unbounded tenant signup. Fails <b>open</b> on a Redis outage - see
 * {@link FixedWindowRateLimiter}, which holds the shared Lua-script logic this filter and {@code
 * McpUserRegistrationRateLimitFilter} (mcp_plan.md Grooming #15) both build on. Keyed on {@code
 * request.getRemoteAddr()}, deliberately not {@code X-Forwarded-For}, which a client could spoof to
 * defeat the limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TenantCreationRateLimitFilter extends OncePerRequestFilter {

    private static final String RATE_LIMITED_PATH = "/api/v1/tenants";

    private final FixedWindowRateLimiter rateLimiter;
    private final RedisKeyPrefix redisKeyPrefix;
    private final long limit;
    private final long windowSeconds;

    TenantCreationRateLimitFilter(
            FixedWindowRateLimiter rateLimiter,
            RedisKeyPrefix redisKeyPrefix,
            @Value("${synapsemcp.rate-limit.tenant-creation.limit:5}") long limit,
            @Value("${synapsemcp.rate-limit.tenant-creation.window-seconds:3600}")
                    long windowSeconds) {
        this.rateLimiter = rateLimiter;
        this.redisKeyPrefix = redisKeyPrefix;
        this.limit = limit;
        this.windowSeconds = windowSeconds;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod())
                && RATE_LIMITED_PATH.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String key = redisKeyPrefix.key("rate_limit:tenant-create:" + request.getRemoteAddr());
        if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
            writeTooManyRequests(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter()
                .write(
                        """
                        {"type":"about:blank","title":"Too Many Requests","status":429,\
                        "detail":"Tenant creation rate limit exceeded, try again later"}""");
    }
}
