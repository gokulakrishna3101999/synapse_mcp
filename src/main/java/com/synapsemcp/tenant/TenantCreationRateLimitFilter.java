package com.synapsemcp.tenant;

import com.synapsemcp.common.RedisKeyPrefix;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-IP fixed-window limiter on {@code POST /api/v1/tenants} (rag_plan.md Stage 1, Grooming #22):
 * the endpoint is open/unauthenticated by design (no admin tier - `plan.md` §9), so this is the
 * only abuse guard against unbounded tenant signup. Fails <b>open</b> (allows the request, logs a
 * warning) if Redis is unreachable - blocking the one public onboarding endpoint over a transient
 * Redis blip would be worse than a temporary unlimited-signup window (`plan.md` §9,
 * `TenantCreationRateLimitFilter` entry). Keyed on {@code request.getRemoteAddr()}, deliberately
 * not {@code X-Forwarded-For}, which a client could spoof to defeat the limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TenantCreationRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantCreationRateLimitFilter.class);
    private static final String RATE_LIMITED_PATH = "/api/v1/tenants";

    /**
     * Atomically increments the counter and sets its TTL only on the first hit, in a single Redis
     * round trip - a plain {@code INCR} followed by a separate, conditional {@code EXPIRE} call has
     * a real gap: if Redis fails only the second call (plausible with the tightened 1s command
     * timeout under a real Redis blip), the key is left with a bumped counter and no TTL, and it
     * never expires again, permanently rate-limiting that IP. A Lua script executes as one atomic
     * unit server-side, so there is no window between the two operations for a partial failure to
     * land in. {@code EXPIRE ... NX} (Redis 7+) would be the simpler equivalent, but this runs
     * against Redis 6.2 here.
     */
    private static final RedisScript<Long> INCREMENT_AND_EXPIRE_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local count = redis.call('INCR', KEYS[1])
                    if count == 1 then
                      redis.call('EXPIRE', KEYS[1], ARGV[1])
                    end
                    return count
                    """,
                    Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyPrefix redisKeyPrefix;
    private final long limit;
    private final long windowSeconds;

    TenantCreationRateLimitFilter(
            StringRedisTemplate redisTemplate,
            RedisKeyPrefix redisKeyPrefix,
            @Value("${synapsemcp.rate-limit.tenant-creation.limit:5}") long limit,
            @Value("${synapsemcp.rate-limit.tenant-creation.window-seconds:3600}")
                    long windowSeconds) {
        this.redisTemplate = redisTemplate;
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
        try {
            Long count =
                    redisTemplate.execute(
                            INCREMENT_AND_EXPIRE_SCRIPT,
                            List.of(key),
                            String.valueOf(windowSeconds));
            if (count != null && count > limit) {
                writeTooManyRequests(response);
                return;
            }
        } catch (DataAccessException e) {
            log.warn(
                    "Rate limiter unreachable, failing open for tenant creation from {}",
                    request.getRemoteAddr(),
                    e);
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
