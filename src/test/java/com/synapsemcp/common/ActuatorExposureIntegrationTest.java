package com.synapsemcp.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;

/**
 * Neither `/actuator/metrics` (Grooming #64) nor `/actuator/scheduledtasks` (Grooming #72) had any
 * automated test before this - both were only ever verified manually against a running app
 * (`plan.md` §9 2026-07-17/2026-07-18). A future accidental removal from {@code
 * management.endpoints.web.exposure.include} would have gone undetected by `./mvnw clean verify`
 * until the next live check.
 */
class ActuatorExposureIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;

    @Test
    void exposesTheIngestionJobsMetric() {
        assertThat(
                        restTemplate
                                .getForEntity(
                                        "/actuator/metrics/synapsemcp.ingestion.jobs", String.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void exposesScheduledTasks() {
        assertThat(restTemplate.getForEntity("/actuator/scheduledtasks", String.class).getBody())
                .contains("IngestionReconciliationJob");
    }

    /**
     * `/actuator/*` is exempted from tenant API-key authentication ({@code
     * ApiKeyAuthenticationFilter}'s own prefix-match exemption) - confirms a plain unauthenticated
     * request to the metrics endpoint still succeeds rather than `401`.
     */
    @Test
    void metricsEndpointRequiresNoAuthentication() {
        assertThat(restTemplate.getForEntity("/actuator/metrics", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
