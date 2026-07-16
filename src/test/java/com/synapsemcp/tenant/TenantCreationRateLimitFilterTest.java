package com.synapsemcp.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.RedisKeyPrefix;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TenantCreationRateLimitFilterTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    private final TenantCreationRateLimitFilter filter =
            new TenantCreationRateLimitFilter(
                    redisTemplate, new RedisKeyPrefix("synapsemcp_test"), 5, 3600);

    @Test
    void allowsRequestsUnderTheLimit() throws Exception {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(3L);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                new MockHttpServletRequest("POST", "/api/v1/tenants"),
                response,
                new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsRequestsOverTheLimitWith429() throws Exception {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(6L);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                new MockHttpServletRequest("POST", "/api/v1/tenants"),
                response,
                new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void failsOpenWhenRedisIsUnreachable() throws Exception {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainCalled = {false};
        MockFilterChain chain =
                new MockFilterChain() {
                    @Override
                    public void doFilter(ServletRequest req, ServletResponse res) {
                        chainCalled[0] = true;
                    }
                };

        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/tenants"), response, chain);

        assertThat(chainCalled[0]).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void onlyAppliesToPostTenantCreation() {
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/tenants")))
                .isTrue();
        assertThat(
                        filter.shouldNotFilter(
                                new MockHttpServletRequest("POST", "/api/v1/knowledgebase")))
                .isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/v1/tenants")))
                .isFalse();
    }
}
