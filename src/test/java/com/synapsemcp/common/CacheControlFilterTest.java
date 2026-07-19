package com.synapsemcp.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CacheControlFilterTest {

    private final CacheControlFilter filter = new CacheControlFilter();

    @Test
    void setsNoStoreOnEverySuccessfulResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/knowledgebase");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    /**
     * Confirms the header survives even when a downstream filter overwrites the response status -
     * this filter must run early enough that a short-circuiting filter (rate limit, auth, body
     * size) can't commit a response without it, since headers set after {@code
     * response.getWriter()} is used are silently dropped by the Servlet API.
     */
    @Test
    void setsNoStoreEvenWhenDownstreamShortCircuitsWithAnErrorResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tenants");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain =
                new MockFilterChain() {
                    @Override
                    public void doFilter(
                            jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                            throws IOException {
                        ((MockHttpServletResponse) res).setStatus(429);
                    }
                };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
}
