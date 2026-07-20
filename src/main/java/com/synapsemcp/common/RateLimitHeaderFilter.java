package com.synapsemcp.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * mcp_plan.md Stage 3: surfaces {@code X-RateLimit-Remaining} on REST responses, matching the
 * "Rate-limit headers on REST responses" checklist item. Unlike {@link CacheControlFilter} (sets
 * its header before {@code doFilter}, since that value never depends on the request), the remaining
 * count is only known once {@code RateLimitAspect} has actually run, deep inside the dispatched
 * call - so this reads {@link RateLimitContext} <i>after</i> {@code filterChain.doFilter(...)}
 * returns. Only ever set for requests that actually passed through a {@code @RateLimited} method;
 * every other response is unaffected (the context is simply never populated). Not read on the MCP
 * path at all - MCP tool calls have no header concept, so that transport is intentionally scoped to
 * structured errors on limit breach only (mcp_plan.md's own wording), never a running remaining
 * count on success.
 *
 * <p>Filter order doesn't affect correctness here, unlike this codebase's other ordered filters -
 * every filter's {@code doFilter()} call blocks until the entire downstream chain (including the
 * MVC dispatch that runs {@code RateLimitAspect}) has finished, so any position sees the fully-set
 * context afterward. Ordered after the existing numbered filters purely for readability.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 4)
public class RateLimitHeaderFilter extends OncePerRequestFilter {

    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            Long remaining = RateLimitContext.get();
            if (remaining != null && !response.isCommitted()) {
                response.setHeader(REMAINING_HEADER, String.valueOf(remaining));
            }
            RateLimitContext.clear();
        }
    }
}
