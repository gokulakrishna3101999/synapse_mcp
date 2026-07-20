package com.synapsemcp.common;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * mcp_plan.md Stage 3, Grooming #3/#5: builds the Redis-backed Bucket4j {@link ProxyManager} used
 * by {@code ProviderRateLimiter}.
 *
 * <p>Uses its own standalone {@link RedisClient}, built directly from {@code
 * spring.data.redis.host}/{@code port}, rather than reusing the app's existing {@code
 * LettuceConnectionFactory} - two things found live (`plan.md` §9) ruled that reuse out:
 *
 * <ol>
 *   <li>Extracting a native connection from a single {@code connectionFactory.getConnection()} call
 *       at bean-creation time and holding onto it failed the moment that request-scoped wrapper's
 *       connection was closed by Spring's own lifecycle management.
 *   <li>Switching to {@code connectionFactory.getRequiredNativeClient()} (the factory's own
 *       long-lived {@code RedisClient}) still eventually hit {@code RedisException: Connection is
 *       closed} under real load - this app deliberately configures a very tight 1-second command
 *       timeout on that shared client (Redis is fail-open everywhere else it's used, so a fast
 *       timeout costs nothing there), which is the wrong setting for Bucket4j's own connection
 *       usage pattern.
 * </ol>
 *
 * <p>A dedicated client, with Lettuce's own default (far more generous) command timeout, avoids
 * both problems and keeps the rate limiter's connection lifecycle fully independent of the app's
 * main data-access Redis usage.
 */
@Configuration
public class RateLimitConfig {

    @Bean(destroyMethod = "shutdown")
    public RedisClient rateLimitRedisClient(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port) {
        return RedisClient.create(RedisURI.Builder.redis(host, port).build());
    }

    @Bean
    public ProxyManager<byte[]> rateLimitProxyManager(RedisClient rateLimitRedisClient) {
        return Bucket4jLettuce.casBasedBuilder(rateLimitRedisClient).build();
    }
}
