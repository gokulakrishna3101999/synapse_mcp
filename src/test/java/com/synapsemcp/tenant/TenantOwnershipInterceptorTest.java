package com.synapsemcp.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Found live (audit session, 2026-07-17): {@code ModelConfigController}'s old per-method {@code
 * requireOwnTenant()} check ran too late - {@code @Valid} request-body validation happens during
 * argument resolution, before the controller method body ever runs, so a cross-tenant caller
 * sending an invalid body got {@code 400} (revealing the required-field shape) instead of {@code
 * 403}. This {@link HandlerInterceptor} enforces ownership in {@code preHandle}, which runs before
 * argument resolution, closing that ordering gap.
 */
class TenantOwnershipInterceptorTest {

    private final TenantOwnershipInterceptor interceptor = new TenantOwnershipInterceptor();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MockHttpServletRequest requestWithTenantIdPathVariable(String tenantId) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("PUT", "/api/v1/tenants/" + tenantId + "/model-config");
        request.setAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("tenantId", tenantId));
        return request;
    }

    @Test
    void allowsRequestWhenPathTenantIdMatchesTheAuthenticatedTenant() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        boolean result =
                interceptor.preHandle(
                        requestWithTenantIdPathVariable(tenantId.toString()),
                        new MockHttpServletResponse(),
                        new Object());

        assertThat(result).isTrue();
    }

    @Test
    void throws403ForbiddenWhenPathTenantIdDoesNotMatchTheAuthenticatedTenant() {
        TenantContext.set(UUID.randomUUID());
        String otherTenantId = UUID.randomUUID().toString();

        assertThatThrownBy(
                        () ->
                                interceptor.preHandle(
                                        requestWithTenantIdPathVariable(otherTenantId),
                                        new MockHttpServletResponse(),
                                        new Object()))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    /**
     * A malformed {@code tenantId} is deliberately left to normal Spring MVC argument resolution
     * (which already produces the correct {@code 400} via {@code ApiExceptionHandler}'s {@code
     * MethodArgumentTypeMismatchException} handler) - this interceptor must not throw its own
     * exception for a value it can't parse.
     */
    @Test
    void doesNotThrowForAMalformedTenantIdLeavesItToNormalArgumentResolution() {
        TenantContext.set(UUID.randomUUID());

        boolean result =
                interceptor.preHandle(
                        requestWithTenantIdPathVariable("not-a-uuid"),
                        new MockHttpServletResponse(),
                        new Object());

        assertThat(result).isTrue();
    }

    @Test
    void allowsRequestsWithNoTenantIdPathVariableAtAll() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        boolean result =
                interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(result).isTrue();
    }
}
