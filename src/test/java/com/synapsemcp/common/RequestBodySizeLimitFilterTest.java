package com.synapsemcp.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestBodySizeLimitFilterTest {

    private final RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(1024);

    /**
     * {@code Content-Length} is known and declares an over-limit body upfront - the cheap fast
     * path.
     */
    @Test
    void rejectsRequestsOverTheLimitWith413BeforeReachingTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tenants");
        request.setContent(new byte[2048]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainCalled = {false};
        MockFilterChain chain =
                new MockFilterChain() {
                    @Override
                    public void doFilter(ServletRequest req, ServletResponse res) {
                        chainCalled[0] = true;
                    }
                };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("Payload Too Large");
        assertThat(chainCalled[0]).isFalse();
    }

    /**
     * Found live (audit session, 2026-07-17): a real client (TestRestTemplate's default Apache
     * HttpClient5 factory) sends every request chunked, with no {@code Content-Length} header at
     * all - {@code getContentLengthLong()} correctly returns {@code -1} for these, which a
     * header-only check would silently treat as "under the limit." This test forces exactly that
     * shape (content bytes available via the stream, but {@code getContentLengthLong()} unknown) to
     * prove the bounded-read fallback catches it anyway.
     */
    @Test
    void rejectsOverLimitBodiesEvenWhenContentLengthIsUnknownLikeARealChunkedClient()
            throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/tenants") {
                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }

                    @Override
                    public int getContentLength() {
                        return -1;
                    }
                };
        request.setContent(new byte[2048]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainCalled = {false};
        MockFilterChain chain =
                new MockFilterChain() {
                    @Override
                    public void doFilter(ServletRequest req, ServletResponse res) {
                        chainCalled[0] = true;
                    }
                };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chainCalled[0]).isFalse();
    }

    @Test
    void allowsRequestsAtOrUnderTheLimitAndWrapsTheBodyForDownstreamReaders() throws Exception {
        byte[] content = "{\"name\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tenants");
        request.setContent(content);
        request.setCharacterEncoding("UTF-8");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] bodySeenDownstream = {null};
        MockFilterChain chain =
                new MockFilterChain() {
                    @Override
                    public void doFilter(ServletRequest req, ServletResponse res)
                            throws IOException {
                        try (BufferedReader reader = req.getReader()) {
                            bodySeenDownstream[0] = reader.readLine();
                        }
                    }
                };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(bodySeenDownstream[0]).isEqualTo("{\"name\":\"ok\"}");
    }

    /**
     * rag_plan.md Stage 4 exemption (`plan.md` §9 2026-07-17): the document-upload endpoint owns
     * its own, much larger cap via Spring's multipart resolver - this filter's 1MB body-buffering
     * check must never run for it at all, or a legitimate 2-20MB upload would be rejected before
     * reaching that endpoint's own logic.
     */
    @Test
    void exemptsTheDocumentUploadEndpointFromTheGlobalLimit() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest(
                        "POST",
                        "/api/v1/knowledgebase/" + java.util.UUID.randomUUID() + "/documents");
        request.setContent(new byte[2048]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainCalled = {false};
        MockFilterChain chain =
                new MockFilterChain() {
                    @Override
                    public void doFilter(ServletRequest req, ServletResponse res) {
                        chainCalled[0] = true;
                    }
                };

        filter.doFilter(request, response, chain);

        assertThat(chainCalled[0])
                .as("the exempted request must reach the chain, not be rejected at 413")
                .isTrue();
    }

    @Test
    void doesNotExemptAGetRequestToTheSamePathShape() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest(
                        "GET",
                        "/api/v1/knowledgebase/" + java.util.UUID.randomUUID() + "/documents");
        request.setContent(new byte[2048]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainCalled = {false};
        MockFilterChain chain =
                new MockFilterChain() {
                    @Override
                    public void doFilter(ServletRequest req, ServletResponse res) {
                        chainCalled[0] = true;
                    }
                };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chainCalled[0]).isFalse();
    }
}
