package com.synapsemcp.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class FixedWindowRateLimiterTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final FixedWindowRateLimiter rateLimiter = new FixedWindowRateLimiter(redisTemplate);

    @Test
    void allowsWhenUnderTheLimit() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(3L);

        assertThat(rateLimiter.tryAcquire("some-key", 5, 3600)).isTrue();
    }

    @Test
    void rejectsWhenOverTheLimit() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(6L);

        assertThat(rateLimiter.tryAcquire("some-key", 5, 3600)).isFalse();
    }

    @Test
    void allowsExactlyAtTheLimit() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(5L);

        assertThat(rateLimiter.tryAcquire("some-key", 5, 3600)).isTrue();
    }

    @Test
    void failsOpenWhenRedisIsUnreachable() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThat(rateLimiter.tryAcquire("some-key", 5, 3600)).isTrue();
    }
}
