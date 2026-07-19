package com.synapsemcp.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sets {@code Cache-Control: no-store} on every response, unconditionally (found missing during an
 * audit pass, `plan.md` §9 2026-07-17 - verified live that no response, including tenant-scoped
 * data and a freshly-issued API key from {@code POST /api/v1/tenants}, carried any cache-control
 * header at all). Every response in this API is either a secret, tenant-scoped data, or an error -
 * nothing here is ever safe to cache, so this applies globally rather than per-endpoint. A {@code
 * Filter}, not a {@code ResponseBodyAdvice}, because several early-exit filters ({@link
 * RequestBodySizeLimitFilter}, {@link com.synapsemcp.tenant.TenantCreationRateLimitFilter}, {@link
 * com.synapsemcp.tenant.ApiKeyAuthenticationFilter}) write and commit a response before Spring
 * MVC's dispatcher ever sees the request, which a {@code ResponseBodyAdvice} would never intercept.
 * Ordered at {@code HIGHEST_PRECEDENCE} so it always runs before any of those short-circuiting
 * filters can commit a response without this header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CacheControlFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        filterChain.doFilter(request, response);
    }
}
