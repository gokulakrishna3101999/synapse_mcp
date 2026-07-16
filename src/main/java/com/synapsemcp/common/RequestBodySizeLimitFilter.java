package com.synapsemcp.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects any request whose body exceeds the configured limit before Spring MVC ever starts parsing
 * it (audit session, 2026-07-17). Found live: neither Tomcat nor Spring MVC cap arbitrary JSON
 * request bodies by default - a 5MB body was fully accepted and parsed on both the open,
 * unauthenticated {@code POST /api/v1/tenants} and the authenticated {@code PUT model-config}
 * endpoints, and on the latter an oversized invalid field got echoed back in full in the error
 * response, doubling the cost.
 *
 * <p>Checks {@code Content-Length} first as a cheap fast path, but does <b>not</b> rely on it alone
 * - verified live that a real client ({@code TestRestTemplate}'s default Apache HttpClient5
 * factory) sends every request with {@code Transfer-Encoding: chunked} and no {@code
 * Content-Length} header at all, which would silently bypass a header-only check. Instead,
 * bounded-reads the body into memory (up to {@code maxBytes + 1}, stopping immediately rather than
 * draining a deliberately huge upload) and rejects if that exceeds the limit, regardless of what
 * the client declared or how it's encoded - then wraps the request so downstream (Jackson, etc.)
 * reads from the already-buffered bytes instead of the original stream. Global (no {@code
 * shouldNotFilter} override) - every current and future JSON endpoint is covered automatically, not
 * just today's two. Runs immediately after {@link CorrelationIdFilter} so a rejection still gets a
 * correlation ID, but before {@link com.synapsemcp.tenant.TenantCreationRateLimitFilter}/{@link
 * com.synapsemcp.tenant.ApiKeyAuthenticationFilter} so an oversized request never wastes a Redis or
 * DB round trip.
 *
 * <p><b>Stage 4 TODO (found during a later audit pass, 2026-07-17):</b> this filter is global and
 * has no exemption mechanism, so it will also fully buffer - and reject at 1MB - the planned {@code
 * POST /api/v1/knowledgebase/{id}/documents} multipart file upload endpoint once it exists, before
 * that endpoint's own Tika-sniffing/validation logic ever runs. rag_plan.md Stage 4 already
 * documents its own, much larger cap ("size cap (20 MB → 413)") - this filter's 1MB default would
 * silently shadow that and reject every legitimate upload over 1MB long before Stage 4's own check
 * is ever reached. When Stage 4 is built, add a {@code shouldNotFilter} exemption for that path so
 * it owns its own 20MB limit - don't let document uploads inherit this JSON-control-plane-sized
 * limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public RequestBodySizeLimitFilter(
            @Value("${synapsemcp.request.max-body-bytes:1048576}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            writePayloadTooLarge(response);
            return;
        }

        byte[] body = readUpTo(request.getInputStream(), maxBytes);
        if (body.length > maxBytes) {
            writePayloadTooLarge(response);
            return;
        }

        filterChain.doFilter(new BufferedBodyHttpServletRequest(request, body), response);
    }

    /**
     * Reads at most {@code limit + 1} bytes - enough to detect an over-limit body without ever
     * fully draining a deliberately huge upload just to reject it.
     */
    private byte[] readUpTo(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while (buffer.size() <= limit && (read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private void writePayloadTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter()
                .write(
                        """
                        {"type":"about:blank","title":"Payload Too Large","status":413,\
                        "detail":"Request body exceeds the maximum allowed size"}""");
    }

    /**
     * Serves the already-buffered, size-checked body back to every downstream reader (Jackson's
     * {@code HttpMessageConverter}, etc.), which otherwise would re-read the original (already
     * partially consumed) request stream.
     */
    private static final class BufferedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        BufferedBodyHttpServletRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream byteStream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return byteStream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException(
                            "async request bodies are not used in this application");
                }

                @Override
                public int read() {
                    return byteStream.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding =
                    getCharacterEncoding() != null
                            ? getCharacterEncoding()
                            : StandardCharsets.UTF_8.name();
            try {
                return new BufferedReader(new InputStreamReader(getInputStream(), encoding));
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Unsupported request character encoding: " + encoding, e);
            }
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
