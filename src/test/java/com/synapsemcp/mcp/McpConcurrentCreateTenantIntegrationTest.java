package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.AbstractIntegrationTest;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * mcp_plan.md Stage 1, Grooming #16: closes a real coverage gap found during a thorough validation
 * pass - the atomic conditional {@code linkTenantIfUnlinked} update (and {@code
 * McpTenantLinkingService}'s single {@code @Transactional} boundary around tenant creation + that
 * update) had only ever been exercised sequentially ({@code
 * McpAuthFlowIntegrationTest#registerConnectAndCreateTenantEndToEnd} calls {@code create_tenant}
 * twice, one after the other, never concurrently) - unlike the equivalent REST-side races (10-KB
 * limit, upload idempotency, model-config first write), which all have genuine concurrent-thread
 * regression tests. Basic Auth is stateless per request (Grooming #1/#17), so N separate MCP client
 * connections authenticating as the same account is a faithful reproduction of N genuinely
 * concurrent {@code create_tenant} calls for one account, not an artificial scenario.
 */
class McpConcurrentCreateTenantIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private McpUserRepository mcpUserRepository;
    @Autowired private com.synapsemcp.tenant.ApiKeyRepository apiKeyRepository;

    @LocalServerPort private int port;

    @Test
    void exactlyOneConcurrentCreateTenantCallWinsTheRaceForOneAccount() throws Exception {
        String username = "mcp-concurrent-create-tenant-" + UUID.randomUUID();
        registerMcpUser(username, "correct-horse-battery-staple");

        int concurrency = 15;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<McpSyncClient> clients = new ArrayList<>();
        try {
            List<Future<McpSchema.CallToolResult>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                McpSyncClient client = buildClient(username, "correct-horse-battery-staple");
                clients.add(client);
                client.initialize();
                int index = i;
                futures.add(
                        executor.submit(
                                () ->
                                        client.callTool(
                                                new McpSchema.CallToolRequest(
                                                        "create_tenant",
                                                        Map.of(
                                                                "name",
                                                                "Concurrent Tenant " + index)))));
            }

            long successCount = 0;
            long alreadyLinkedDenialCount = 0;
            for (Future<McpSchema.CallToolResult> future : futures) {
                McpSchema.CallToolResult result = future.get();
                if (Boolean.TRUE.equals(result.isError())) {
                    String text = ((McpSchema.TextContent) result.content().get(0)).text();
                    assertThat(text).contains("already linked to a tenant");
                    alreadyLinkedDenialCount++;
                } else {
                    successCount++;
                }
            }

            assertThat(successCount).isEqualTo(1);
            assertThat(alreadyLinkedDenialCount).isEqualTo(concurrency - 1);
        } finally {
            for (McpSyncClient client : clients) {
                client.close();
            }
            executor.shutdown();
        }

        McpUser user = mcpUserRepository.findByUsername(username).orElseThrow();
        UUID tenantId = user.getTenantId();
        assertThat(tenantId).isNotNull();
        assertThat(
                        apiKeyRepository.findAll().stream()
                                .filter(k -> k.getTenantId().equals(tenantId)))
                .hasSize(1);
    }

    private void registerMcpUser(String username, String password) {
        ResponseEntity<RegisterMcpUserResponse> response =
                restTemplate.postForEntity(
                        "/api/v1/mcp-users/register",
                        new RegisterMcpUserRequest(username, password, null),
                        RegisterMcpUserResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private McpSyncClient buildClient(String username, String password) {
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
        return McpClient.sync(transport).build();
    }
}
