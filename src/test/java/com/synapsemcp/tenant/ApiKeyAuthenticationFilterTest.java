package com.synapsemcp.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyAuthenticationFilterTest {

    private final ApiKeyRepository apiKeyRepository = mock(ApiKeyRepository.class);
    private final ApiKeyAuthenticationFilter filter =
            new ApiKeyAuthenticationFilter(apiKeyRepository);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void rejectsRequestWithNoAuthorizationHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/knowledgebase");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(apiKeyRepository);
    }

    @Test
    void rejectsUnknownApiKey() throws Exception {
        when(apiKeyRepository.findTenantIdByKeyHash(ApiKeyHasher.sha256Hex("bad-key")))
                .thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/knowledgebase");
        request.addHeader("Authorization", "Bearer bad-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void setsTenantContextForValidKeyAndClearsItAfterward() throws Exception {
        UUID tenantId = UUID.randomUUID();
        when(apiKeyRepository.findTenantIdByKeyHash(ApiKeyHasher.sha256Hex("good-key")))
                .thenReturn(Optional.of(tenantId));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/knowledgebase");
        request.addHeader("Authorization", "Bearer good-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        UUID[] tenantSeenDuringChain = new UUID[1];
        MockFilterChain chain =
                new MockFilterChain() {
                    @Override
                    public void doFilter(
                            jakarta.servlet.ServletRequest req,
                            jakarta.servlet.ServletResponse res) {
                        tenantSeenDuringChain[0] = TenantContext.get();
                    }
                };

        filter.doFilter(request, response, chain);

        assertThat(tenantSeenDuringChain[0]).isEqualTo(tenantId);
        assertThat(TenantContext.get()).isNull();
    }

    /**
     * Found via live testing with Postgres actually stopped mid-flight (not a mocked exception path
     * picked in hindsight): this filter runs before Spring MVC's dispatcher, so {@code
     * ApiExceptionHandler}'s {@code @RestControllerAdvice} can never see an exception thrown here -
     * without this catch, a real DB outage on this exact repository call leaked as a raw,
     * non-RFC-7807 {@code 500} for every authenticated endpoint.
     */
    @Test
    void returns503WithRfc7807BodyWhenTheApiKeyLookupItselfFailsBecauseTheDatabaseIsDown()
            throws Exception {
        when(apiKeyRepository.findTenantIdByKeyHash(ApiKeyHasher.sha256Hex("some-key")))
                .thenThrow(
                        new DataAccessResourceFailureException(
                                "Unable to acquire JDBC Connection"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/knowledgebase");
        request.addHeader("Authorization", "Bearer some-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("Database temporarily unavailable");
    }

    @Test
    void bypassesActuatorAndOpenTenantCreationEndpoint() {
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health")))
                .isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/v1/tenants")))
                .isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/tenants")))
                .isFalse();
        assertThat(
                        filter.shouldNotFilter(
                                new MockHttpServletRequest("GET", "/api/v1/knowledgebase")))
                .isFalse();
    }
}
