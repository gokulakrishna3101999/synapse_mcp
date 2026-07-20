package com.synapsemcp.mcp;

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
 * Per-IP fixed-window limiter on {@code POST /api/v1/mcp-users/register} (mcp_plan.md Grooming
 * #15): the endpoint is open/unauthenticated like {@code POST /api/v1/tenants}, and registration
 * volume - not tenant-linking, which is self-limiting to one successful call per account (Grooming
 * #13) - is the real abuse surface here, including brute-forcing/enumerating real {@code api_key}
 * values via the registration endpoint's own validation branch. Reuses the exact same pattern as
 * {@code TenantCreationRateLimitFilter} via the shared {@link FixedWindowRateLimiter}: 5/hour/IP,
 * fails open on a Redis outage, keyed on {@code request.getRemoteAddr()} (not {@code
 * X-Forwarded-For}, which a client could spoof).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class McpUserRegistrationRateLimitFilter extends OncePerRequestFilter {

    private static final String RATE_LIMITED_PATH = "/api/v1/mcp-users/register";

    private final FixedWindowRateLimiter rateLimiter;
    private final RedisKeyPrefix redisKeyPrefix;
    private final long limit;
    private final long windowSeconds;

    McpUserRegistrationRateLimitFilter(
            FixedWindowRateLimiter rateLimiter,
            RedisKeyPrefix redisKeyPrefix,
            @Value("${synapsemcp.rate-limit.mcp-user-registration.limit:5}") long limit,
            @Value("${synapsemcp.rate-limit.mcp-user-registration.window-seconds:3600}")
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
        String key = redisKeyPrefix.key("rate_limit:mcp-user-register:" + request.getRemoteAddr());
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
                        "detail":"MCP user registration rate limit exceeded, try again later"}""");
    }
}
