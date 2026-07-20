package com.synapsemcp.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.AbstractIntegrationTest;
import com.synapsemcp.mcp.RegisterMcpUserRequest;
import com.synapsemcp.mcp.RegisterMcpUserResponse;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
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

    @LocalServerPort private int port;

    private McpSyncClient client;

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void exposesTheIngestionJobsMetric() {
        assertThat(
                        restTemplate
                                .getForEntity(
                                        "/actuator/metrics/synapsemcp.ingestion.jobs", String.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * mcp_plan.md Cross-Cutting Concerns, Grooming #10: unlike {@code synapsemcp.ingestion.jobs}
     * (an eagerly-registered {@code Gauge}), {@code synapsemcp.mcp.tool.invocations} is a lazily
     * registered {@code Counter} that only exists once a real {@code @McpTool} call has happened -
     * so this drives one real `create_tenant` call over genuine Streamable HTTP first, matching
     * {@code McpAuthFlowIntegrationTest}'s own pattern, before checking the metric is reachable.
     */
    @Test
    void exposesTheMcpToolInvocationsMetricAfterARealToolCall() {
        String username = "actuator-metrics-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";
        restTemplate.postForEntity(
                "/api/v1/mcp-users/register",
                new RegisterMcpUserRequest(username, password, null),
                RegisterMcpUserResponse.class);

        String credentials =
                Base64.getEncoder()
                        .encodeToString(
                                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        var transport =
                HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .requestBuilder(
                                HttpRequest.newBuilder()
                                        .header("Authorization", "Basic " + credentials))
                        .build();
        client = McpClient.sync(transport).build();
        client.initialize();
        client.callTool(
                new McpSchema.CallToolRequest("create_tenant", Map.of("name", "Metrics Tenant")));

        assertThat(
                        restTemplate
                                .getForEntity(
                                        "/actuator/metrics/synapsemcp.mcp.tool.invocations",
                                        String.class)
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
