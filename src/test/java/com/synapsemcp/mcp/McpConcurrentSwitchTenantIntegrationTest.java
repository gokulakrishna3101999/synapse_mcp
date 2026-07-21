package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.AbstractIntegrationTest;
import com.synapsemcp.common.RedisKeyPrefix;
import com.synapsemcp.tenant.CreateTenantRequest;
import com.synapsemcp.tenant.CreateTenantResponse;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * mcp_plan.md Grooming #27: the 5-tenant cap's pessimistic-lock race guard ({@code
 * McpUserRepository.lockById}, added in Grooming #26 specifically for this scenario) had only ever
 * been exercised sequentially - unlike every other count-check race in this codebase ({@code
 * McpConcurrentCreateTenantIntegrationTest} for the one-create_tenant invariant, {@code
 * KnowledgeBaseIntegrationTest#concurrentKnowledgeBaseCreationNeverExceedsTheLimit} for the
 * 10-KB-per-tenant limit), which all have genuine concurrent-thread regression tests. The exact
 * race the lock exists to prevent: an account sitting one slot below the cap receives N
 * simultaneous {@code switch_tenant} calls, each targeting a <em>different</em> brand-new tenant -
 * without the lock, several could pass the count check before any of them commits its history row,
 * pushing the account past 5. Basic Auth is stateless per request (Grooming #1/#17), so N separate
 * MCP client connections authenticating as the same account faithfully reproduce N genuinely
 * concurrent calls.
 */
class McpConcurrentSwitchTenantIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private McpUserRepository mcpUserRepository;
    @Autowired private McpUserTenantLinkRepository linkRepository;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private RedisKeyPrefix redisKeyPrefix;

    @LocalServerPort private int port;

    private record TenantFixture(UUID tenantId, String apiKey) {}

    @Test
    void concurrentSwitchesToDifferentNewTenantsNeverPushTheAccountPastTheCap() throws Exception {
        // 4 tenants to fill slots 1-4 sequentially, then 8 more all racing for the single
        // remaining slot 5.
        TenantFixture[] warmupTenants = new TenantFixture[4];
        for (int i = 0; i < warmupTenants.length; i++) {
            warmupTenants[i] = createRestTenant("Race Warmup Tenant " + i);
        }
        int raceConcurrency = 8;
        TenantFixture[] raceTenants = new TenantFixture[raceConcurrency];
        for (int i = 0; i < raceTenants.length; i++) {
            raceTenants[i] = createRestTenant("Race Contender Tenant " + i);
        }

        String username = "mcp-concurrent-switch-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";
        registerMcpUser(username, password);

        ExecutorService executor = Executors.newFixedThreadPool(raceConcurrency);
        List<McpSyncClient> clients = new ArrayList<>();
        try {
            McpSyncClient warmupClient = buildClient(username, password);
            clients.add(warmupClient);
            warmupClient.initialize();
            for (TenantFixture tenant : warmupTenants) {
                McpSchema.CallToolResult result =
                        warmupClient.callTool(
                                new McpSchema.CallToolRequest(
                                        "switch_tenant",
                                        Map.of(
                                                "tenantId",
                                                tenant.tenantId().toString(),
                                                "apiKey",
                                                tenant.apiKey())));
                assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            }

            List<Future<McpSchema.CallToolResult>> futures = new ArrayList<>();
            for (int i = 0; i < raceConcurrency; i++) {
                McpSyncClient client = buildClient(username, password);
                clients.add(client);
                client.initialize();
                TenantFixture target = raceTenants[i];
                futures.add(
                        executor.submit(
                                () ->
                                        client.callTool(
                                                new McpSchema.CallToolRequest(
                                                        "switch_tenant",
                                                        Map.of(
                                                                "tenantId",
                                                                target.tenantId().toString(),
                                                                "apiKey",
                                                                target.apiKey())))));
            }

            long successCount = 0;
            long capDenialCount = 0;
            for (Future<McpSchema.CallToolResult> future : futures) {
                McpSchema.CallToolResult result = future.get();
                if (Boolean.TRUE.equals(result.isError())) {
                    String text = ((McpSchema.TextContent) result.content().get(0)).text();
                    assertThat(text).contains("maximum of 5 tenants");
                    capDenialCount++;
                } else {
                    successCount++;
                }
            }

            assertThat(successCount)
                    .as("exactly one racing switch may claim the last remaining slot")
                    .isEqualTo(1);
            assertThat(capDenialCount).isEqualTo(raceConcurrency - 1);
        } finally {
            for (McpSyncClient client : clients) {
                client.close();
            }
            executor.shutdown();
        }

        UUID mcpUserId = mcpUserRepository.findByUsername(username).orElseThrow().getId();
        assertThat(linkRepository.countByMcpUserId(mcpUserId))
                .as("the persisted history must show exactly 5 distinct tenants, never more")
                .isEqualTo(5);
    }

    /**
     * Clears the tenant-creation rate-limit key before every call (same pattern as {@code
     * KnowledgeBaseIntegrationTest#createConfiguredTenant}) - this test creates 12 fixture tenants
     * in one method, far past the real 5/hour/IP limit.
     */
    private TenantFixture createRestTenant(String name) {
        Set<String> rateLimitKeys =
                redisTemplate.keys(redisKeyPrefix.key("rate_limit:tenant-create:*"));
        if (rateLimitKeys != null && !rateLimitKeys.isEmpty()) {
            redisTemplate.delete(rateLimitKeys);
        }
        CreateTenantResponse response =
                restTemplate.postForObject(
                        "/api/v1/tenants",
                        new CreateTenantRequest(name),
                        CreateTenantResponse.class);
        return new TenantFixture(response.tenantId(), response.apiKey());
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
