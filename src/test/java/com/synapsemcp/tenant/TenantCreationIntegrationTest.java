package com.synapsemcp.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.AbstractIntegrationTest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class TenantCreationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private ApiKeyRepository apiKeyRepository;

    @Test
    void createsATenantAndTheReturnedApiKeyResolvesToItsTenantContext() {
        ResponseEntity<CreateTenantResponse> response =
                restTemplate.postForEntity(
                        "/api/v1/tenants",
                        new CreateTenantRequest("Acme Corp"),
                        CreateTenantResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        CreateTenantResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.tenantId()).isNotNull();
        assertThat(body.name()).isEqualTo("Acme Corp");
        assertThat(body.apiKey()).isNotBlank();

        Optional<UUID> resolvedTenantId =
                apiKeyRepository.findTenantIdByKeyHash(ApiKeyHasher.sha256Hex(body.apiKey()));
        assertThat(resolvedTenantId).contains(body.tenantId());
    }

    @Test
    void tenantIsolation_creatingTenantBDoesNotAffectTenantAsApiKeyResolution() {
        CreateTenantResponse tenantA =
                restTemplate.postForObject(
                        "/api/v1/tenants",
                        new CreateTenantRequest("Tenant A"),
                        CreateTenantResponse.class);
        CreateTenantResponse tenantB =
                restTemplate.postForObject(
                        "/api/v1/tenants",
                        new CreateTenantRequest("Tenant B"),
                        CreateTenantResponse.class);

        assertThat(tenantA.tenantId()).isNotEqualTo(tenantB.tenantId());
        assertThat(tenantA.apiKey()).isNotEqualTo(tenantB.apiKey());

        UUID resolvedA =
                apiKeyRepository
                        .findTenantIdByKeyHash(ApiKeyHasher.sha256Hex(tenantA.apiKey()))
                        .orElseThrow();
        UUID resolvedB =
                apiKeyRepository
                        .findTenantIdByKeyHash(ApiKeyHasher.sha256Hex(tenantB.apiKey()))
                        .orElseThrow();

        assertThat(resolvedA).isEqualTo(tenantA.tenantId());
        assertThat(resolvedB).isEqualTo(tenantB.tenantId());
        assertThat(resolvedA).isNotEqualTo(resolvedB);
    }

    @Test
    void rejectsUnauthenticatedRequestsToOtherEndpointsButNotTenantCreation() {
        ResponseEntity<String> unauthenticated =
                restTemplate.getForEntity("/api/v1/knowledgebase", String.class);
        assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<CreateTenantResponse> tenantCreation =
                restTemplate.postForEntity(
                        "/api/v1/tenants",
                        new CreateTenantRequest("Open Endpoint Tenant"),
                        CreateTenantResponse.class);
        assertThat(tenantCreation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void sixthTenantCreationRequestFromSameIpIsRateLimited() {
        for (int i = 0; i < 5; i++) {
            ResponseEntity<CreateTenantResponse> response =
                    restTemplate.postForEntity(
                            "/api/v1/tenants",
                            new CreateTenantRequest("Tenant " + i),
                            CreateTenantResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<String> sixth =
                restTemplate.postForEntity(
                        "/api/v1/tenants", new CreateTenantRequest("Tenant 6"), String.class);

        assertThat(sixth.getStatusCode().value()).isEqualTo(429);
    }

    /**
     * Found live (audit session, 2026-07-16 session): {@code CreateTenantRequest.name} has no
     * {@code @Size} bound, so a name past Hibernate's implicit {@code varchar(255)} default hit a
     * raw Postgres "value too long" error and leaked as an unhandled {@code 500}.
     */
    @Test
    void oversizedTenantNameReturns400NotAServerError() {
        String oversizedName = "A".repeat(500);

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        "/api/v1/tenants", new CreateTenantRequest(oversizedName), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * Found live (audit session, 2026-07-17): neither Tomcat nor Spring MVC cap arbitrary JSON
     * request bodies by default - a 5MB body was fully accepted, parsed, and pushed through to a DB
     * insert attempt on this exact, open/unauthenticated endpoint, gated only by the weak 5/hour/IP
     * rate limiter. Fixed via {@code RequestBodySizeLimitFilter}, rejecting on {@code
     * Content-Length} before Spring MVC starts parsing.
     */
    @Test
    void oversizedRequestBodyReturns413BeforeEverReachingValidationOrTheDatabase() {
        String oversizedName = "A".repeat(2_000_000);

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        "/api/v1/tenants", new CreateTenantRequest(oversizedName), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
    }
}
