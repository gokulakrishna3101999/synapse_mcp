package com.synapsemcp.tenant;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Rejects a request whose {@code {tenantId}} path variable doesn't match the authenticated caller's
 * own {@link TenantContext} - before Spring MVC does anything else with the request, including
 * {@code @Valid} request-body validation (audit session, 2026-07-17). {@code @Valid} runs during
 * argument resolution, which happens for every method parameter (in declaration order) before the
 * controller method body ever runs - so a per-controller-method check like {@code
 * ModelConfigController}'s old {@code requireOwnTenant()} can never win a race against body
 * validation no matter where the {@code tenantId} parameter sits in the method signature. Found
 * live: a caller with a valid API key for a *different* tenant, sending an invalid body to a
 * cross-tenant request, got {@code 400} (revealing the required-field shape) instead of {@code
 * 403}. A {@link HandlerInterceptor#preHandle} runs in {@code DispatcherServlet}'s interceptor
 * chain, strictly before argument resolution, so it can enforce authorization first regardless of
 * body validity.
 *
 * <p>Registered against {@code /api/v1/tenants/{tenantId}/**} (see {@code WebMvcConfig}) - a
 * wildcard pattern rather than one path per controller, so any future tenant-scoped endpoint under
 * this prefix is covered automatically without needing to remember to add it here.
 *
 * <p>A malformed (non-UUID) {@code tenantId} is deliberately <b>not</b> handled here - {@code
 * UUID.fromString} would throw, and this class lets that case fall through to normal argument
 * resolution instead, which already produces the correct {@code 400} via {@code
 * ApiExceptionHandler}'s {@code MethodArgumentTypeMismatchException} handler. Duplicating that here
 * would just be two code paths validating the same thing.
 */
@Component
public class TenantOwnershipInterceptor implements HandlerInterceptor {

    private static final String TENANT_ID_PATH_VARIABLE = "tenantId";

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        String rawTenantId = pathVariable(request, TENANT_ID_PATH_VARIABLE);
        if (rawTenantId == null) {
            return true;
        }
        UUID tenantId;
        try {
            tenantId = UUID.fromString(rawTenantId);
        } catch (IllegalArgumentException e) {
            return true;
        }
        if (!tenantId.equals(TenantContext.get())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "Forbidden", "cannot access another tenant's resources");
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private String pathVariable(HttpServletRequest request, String name) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attribute instanceof Map<?, ?> variables)) {
            return null;
        }
        return ((Map<String, String>) variables).get(name);
    }
}
