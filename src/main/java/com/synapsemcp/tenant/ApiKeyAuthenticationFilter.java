package com.synapsemcp.tenant;

import com.synapsemcp.common.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves {@code Authorization: Bearer <key>} against {@code api_keys.key_hash} (SHA-256) and
 * populates {@link TenantContext} for the duration of the request (rag_plan.md Stage 0). Bypasses
 * {@code /actuator/*}, {@code POST /api/v1/tenants}, and (mcp_plan.md Stage 1) {@code POST
 * /api/v1/mcp-users/register} - all open/unauthenticated by design, no admin tier exists (`plan.md`
 * §9). Also bypasses the MCP transport endpoint ({@code /mcp}, Grooming #17's Streamable HTTP path)
 * entirely - MCP requests authenticate via Spring Security's own HTTP Basic chain (Grooming
 * #11/#12) against {@code mcp_users}, a completely different credential scheme from this filter's
 * REST bearer tokens; without this bypass, every MCP request would be rejected with a {@code 401}
 * by this filter before Spring Security's chain ever got a chance to run, since this filter is
 * ordered well before Spring Security's own (Boot's default {@code
 * SecurityProperties.DEFAULT_FILTER_ORDER} runs at {@code HIGHEST_PRECEDENCE + 100}). Runs outside
 * Spring MVC's dispatcher, so on failure it writes a minimal RFC 7807 body directly rather than
 * delegating to the {@code @RestControllerAdvice} handler (which only sees exceptions thrown
 * *inside* the dispatched request) - this includes a Postgres-unavailable failure on the {@code
 * api_keys} lookup itself (verified live: with Postgres stopped mid-flight, every *authenticated*
 * endpoint hits this lookup before any controller code runs, so {@code ApiExceptionHandler}'s
 * {@code CannotCreateTransactionException} -&gt; {@code 503} mapping - correct for the open,
 * filter-bypassing {@code POST /api/v1/tenants} - never gets a chance to apply here and the failure
 * would otherwise leak as a raw, non-RFC-7807 {@code 500}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String OPEN_TENANT_CREATION_PATH = "/api/v1/tenants";
    private static final String OPEN_MCP_USER_REGISTRATION_PATH = "/api/v1/mcp-users/register";
    private static final String MCP_TRANSPORT_PATH = "/mcp";

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyAuthenticationFilter(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/actuator") || MCP_TRANSPORT_PATH.equals(path)) {
            return true;
        }
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        return OPEN_TENANT_CREATION_PATH.equals(path)
                || OPEN_MCP_USER_REGISTRATION_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
                writeUnauthorized(response, "Missing or malformed Authorization header");
                return;
            }
            String rawKey = authHeader.substring(BEARER_PREFIX.length()).trim();
            Optional<UUID> tenantId;
            try {
                tenantId = apiKeyRepository.findTenantIdByKeyHash(ApiKeyHasher.sha256Hex(rawKey));
            } catch (DataAccessResourceFailureException | CannotCreateTransactionException e) {
                writeServiceUnavailable(response);
                return;
            }
            if (tenantId.isEmpty()) {
                writeUnauthorized(response, "Invalid API key");
                return;
            }
            TenantContext.set(tenantId.get());
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String detail) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter()
                .write(
                        """
                        {"type":"about:blank","title":"Unauthorized","status":401,"detail":"%s"}"""
                                .formatted(detail));
    }

    private void writeServiceUnavailable(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter()
                .write(
                        """
                        {"type":"about:blank","title":"Service Unavailable","status":503,"detail":"Database temporarily unavailable"}""");
    }
}
