package com.synapsemcp.common;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Shared per-key fixed-window rate limiter (rag_plan.md Stage 1 Grooming #22, mcp_plan.md Grooming
 * #15) - extracted once a second real caller ({@code McpUserRegistrationRateLimitFilter}) needed
 * the identical Lua-script logic {@code TenantCreationRateLimitFilter} already had, rather than
 * copy-pasting it a second time.
 *
 * <p>Atomically increments the counter and sets its TTL only on the first hit, in a single Redis
 * round trip - a plain {@code INCR} followed by a separate, conditional {@code EXPIRE} call has a
 * real gap: if Redis fails only the second call (plausible under a real Redis blip with this
 * project's own tightened 1s command timeout), the key is left with a bumped counter and no TTL,
 * and it never expires again, permanently rate-limiting that key. A Lua script executes as one
 * atomic unit server-side, so there is no window between the two operations for a partial failure
 * to land in. {@code EXPIRE ... NX} (Redis 7+) would be the simpler equivalent, but this runs
 * against Redis 6.2 here.
 *
 * <p>Fails <b>open</b> on any Redis error - every caller of this component guards an
 * open/unauthenticated endpoint with no other abuse protection, so blocking it over a transient
 * Redis blip would be worse than a temporary unlimited-request window (same reasoning as {@code
 * TenantCreationRateLimitFilter}'s own original Javadoc).
 */
@Component
public class FixedWindowRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(FixedWindowRateLimiter.class);

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

    FixedWindowRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * @param key the full, already-prefixed Redis key (see {@link RedisKeyPrefix}) identifying the
     *     caller being limited (e.g. an IP address) within its own namespace.
     * @return {@code true} if the request should proceed (under the limit, or Redis failed open);
     *     {@code false} if the limit has been exceeded and the caller should be rejected.
     */
    public boolean tryAcquire(String key, long limit, long windowSeconds) {
        try {
            Long count =
                    redisTemplate.execute(
                            INCREMENT_AND_EXPIRE_SCRIPT,
                            List.of(key),
                            String.valueOf(windowSeconds));
            return count == null || count <= limit;
        } catch (DataAccessException e) {
            log.warn("Rate limiter unreachable for key {}, failing open", key, e);
            return true;
        }
    }
}
