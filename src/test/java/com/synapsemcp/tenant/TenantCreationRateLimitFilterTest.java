package com.synapsemcp.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.FixedWindowRateLimiter;
import com.synapsemcp.common.RedisKeyPrefix;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TenantCreationRateLimitFilterTest {

    private final FixedWindowRateLimiter rateLimiter = mock(FixedWindowRateLimiter.class);

    private final TenantCreationRateLimitFilter filter =
            new TenantCreationRateLimitFilter(
                    rateLimiter, new RedisKeyPrefix("synapsemcp_test"), 5, 3600);

    @Test
    void allowsRequestsUnderTheLimit() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), anyLong(), anyLong())).thenReturn(true);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                new MockHttpServletRequest("POST", "/api/v1/tenants"),
                response,
                new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsRequestsOverTheLimitWith429() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), anyLong(), anyLong())).thenReturn(false);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                new MockHttpServletRequest("POST", "/api/v1/tenants"),
                response,
                new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
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
