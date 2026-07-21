package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.AbstractIntegrationTest;
import com.synapsemcp.common.RedisKeyPrefix;
import com.synapsemcp.embedding.EmbeddingModelFactory;
import com.synapsemcp.tenant.ConfigureModelRequest;
import com.synapsemcp.tenant.CreateTenantRequest;
import com.synapsemcp.tenant.CreateTenantResponse;
import com.synapsemcp.tenant.ModelConfigResponse;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * User-requested (2026-07-21) {@code switch_tenant} tool: a brand-new, never-linked MCP account can
 * attach directly to an already-existing REST-created tenant by proving its API key, move to a
 * second existing tenant the same way, and have every other tool act on whichever tenant it most
 * recently switched to - proven end to end over genuine Streamable HTTP against a real Postgres
 * database, not just the unit-level mocks in {@code McpTenantSwitchingServiceTest}.
 */
class McpSwitchTenantIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private McpUserRepository mcpUserRepository;
    @Autowired private McpUserTenantLinkRepository linkRepository;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private RedisKeyPrefix redisKeyPrefix;

    @MockitoBean private EmbeddingModelFactory embeddingModelFactory;

    @LocalServerPort private int port;

    private McpSyncClient client;

    @BeforeEach
    void stubEmbeddingModel() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.dimensions()).thenReturn(1536);
        when(embeddingModelFactory.getEmbeddingModel(any())).thenReturn(embeddingModel);
    }

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    private record TenantFixture(UUID tenantId, String apiKey) {}

    /**
     * Clears the tenant-creation rate-limit key before every call (same pattern as {@code
     * KnowledgeBaseIntegrationTest#createConfiguredTenant}) so the 5-tenant-cap test below, which
     * creates 6 tenants in one method, doesn't spuriously hit the real 5/hour/IP limit.
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

    /**
     * Same as {@link #createRestTenant}, plus the model config that {@code create_knowledge_base}
     * requires (the embedding-dimension probe runs against the stubbed {@code
     * EmbeddingModelFactory}, so no real provider call ever happens).
     */
    private TenantFixture createConfiguredRestTenant(String name) {
        TenantFixture tenant = createRestTenant(name);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenant.apiKey());
        ResponseEntity<ModelConfigResponse> configResponse =
                restTemplate.exchange(
                        "/api/v1/tenants/{tenantId}/model-config",
                        HttpMethod.PUT,
                        new HttpEntity<>(
                                new ConfigureModelRequest(
                                        "openai",
                                        "gpt-4o",
                                        "openai",
                                        "text-embedding-3-small",
                                        "sk-fake-chat-key",
                                        "sk-fake-embed-key"),
                                headers),
                        ModelConfigResponse.class,
                        tenant.tenantId());
        assertThat(configResponse.getStatusCode().is2xxSuccessful()).isTrue();
        return tenant;
    }

    private List<String> listKnowledgeBaseIds() {
        McpSchema.CallToolResult result =
                client.callTool(new McpSchema.CallToolRequest("list_knowledge_bases", Map.of()));
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        try {
            List<Map<String, Object>> kbs =
                    new com.fasterxml.jackson.databind.ObjectMapper().readValue(text, List.class);
            return kbs.stream().map(kb -> (String) kb.get("id")).toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void switchTenantAttachesAFreshAccountThenMovesItBetweenTwoExistingTenants() {
        TenantFixture tenantA = createRestTenant("Switch Test Tenant A");
        TenantFixture tenantB = createRestTenant("Switch Test Tenant B");

        String username = "switch-test-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";
        ResponseEntity<RegisterMcpUserResponse> registration =
                restTemplate.postForEntity(
                        "/api/v1/mcp-users/register",
                        new RegisterMcpUserRequest(username, password, null),
                        RegisterMcpUserResponse.class);
        assertThat(registration.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registration.getBody().tenantId()).isNull();

        client = buildClient(username, password);
        client.initialize();

        // A brand-new, never-linked account can attach directly via switch_tenant - no
        // create_tenant call needed at all.
        Map<String, Object> switchToA =
                callTool(
                        "switch_tenant",
                        Map.of(
                                "tenantId",
                                tenantA.tenantId().toString(),
                                "apiKey",
                                tenantA.apiKey()));
        assertThat(switchToA.get("tenantId")).isEqualTo(tenantA.tenantId().toString());
        assertThat(mcpUserRepository.findByUsername(username).orElseThrow().getTenantId())
                .isEqualTo(tenantA.tenantId());

        // Every other tool now acts on tenant A.
        Map<String, Object> tenantDetailA = callTool("get_tenant", Map.of());
        assertThat(tenantDetailA.get("tenantId")).isEqualTo(tenantA.tenantId().toString());

        // Switching to a second, unrelated existing tenant - never touched by this account
        // before - works exactly the same way, proving no persisted "which tenants can I reach"
        // relationship is needed at all, only the API key itself.
        Map<String, Object> switchToB =
                callTool(
                        "switch_tenant",
                        Map.of(
                                "tenantId",
                                tenantB.tenantId().toString(),
                                "apiKey",
                                tenantB.apiKey()));
        assertThat(switchToB.get("tenantId")).isEqualTo(tenantB.tenantId().toString());
        assertThat(mcpUserRepository.findByUsername(username).orElseThrow().getTenantId())
                .isEqualTo(tenantB.tenantId());

        Map<String, Object> tenantDetailB = callTool("get_tenant", Map.of());
        assertThat(tenantDetailB.get("tenantId")).isEqualTo(tenantB.tenantId().toString());
    }

    /**
     * The security-critical half of the feature that {@code
     * switchTenantAttachesAFreshAccountThenMovesItBetweenTwoExistingTenants} doesn't prove: after a
     * switch, every other tool must act on the <em>new</em> tenant's data and the old tenant's data
     * must become genuinely unreachable - not just {@code get_tenant} reporting a different id.
     * Found during an architect-level validation round (mcp_plan.md Grooming #27): the existing
     * switch tests only ever checked tenant <em>identity</em> after a switch, never data scoping.
     */
    @Test
    void switchingScopesEveryOtherToolToTheNewTenantsDataOnly() {
        TenantFixture tenantA = createConfiguredRestTenant("Isolation Tenant A");
        TenantFixture tenantB = createConfiguredRestTenant("Isolation Tenant B");
        String username = "switch-isolation-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";
        restTemplate.postForEntity(
                "/api/v1/mcp-users/register",
                new RegisterMcpUserRequest(username, password, null),
                RegisterMcpUserResponse.class);
        client = buildClient(username, password);
        client.initialize();

        // Attached to tenant A: create a knowledge base there, and it's the only one visible.
        callTool(
                "switch_tenant",
                Map.of("tenantId", tenantA.tenantId().toString(), "apiKey", tenantA.apiKey()));
        Map<String, Object> kbAlpha = callTool("create_knowledge_base", Map.of("name", "kb-alpha"));
        String kbAlphaId = (String) kbAlpha.get("id");
        assertThat(listKnowledgeBaseIds()).containsExactly(kbAlphaId);

        // Switched to tenant B: tenant A's knowledge base disappears from every tool's view.
        callTool(
                "switch_tenant",
                Map.of("tenantId", tenantB.tenantId().toString(), "apiKey", tenantB.apiKey()));
        assertThat(listKnowledgeBaseIds())
                .as("immediately after switching, the new tenant has no knowledge bases at all")
                .isEmpty();

        Map<String, Object> kbBeta = callTool("create_knowledge_base", Map.of("name", "kb-beta"));
        String kbBetaId = (String) kbBeta.get("id");
        assertThat(listKnowledgeBaseIds()).containsExactly(kbBetaId);

        McpSchema.CallToolResult crossTenantWrite =
                client.callTool(
                        new McpSchema.CallToolRequest(
                                "update_knowledge_base",
                                Map.of("knowledgeBaseName", "kb-alpha", "newName", "hijacked")));
        assertThat(crossTenantWrite.isError())
                .as("a tool call against the previous tenant's knowledge base must be rejected")
                .isEqualTo(Boolean.TRUE);
        assertThat(((McpSchema.TextContent) crossTenantWrite.content().get(0)).text())
                .contains("not found");

        // Switching back restores tenant A's view - and tenant B's data is now the unreachable one.
        callTool(
                "switch_tenant",
                Map.of("tenantId", tenantA.tenantId().toString(), "apiKey", tenantA.apiKey()));
        assertThat(listKnowledgeBaseIds()).containsExactly(kbAlphaId);
        McpSchema.CallToolResult reverseCrossTenantWrite =
                client.callTool(
                        new McpSchema.CallToolRequest(
                                "update_knowledge_base",
                                Map.of("knowledgeBaseName", "kb-beta", "newName", "hijacked")));
        assertThat(reverseCrossTenantWrite.isError()).isEqualTo(Boolean.TRUE);
    }

    /**
     * The switch-first ordering of the "create_tenant only ever succeeds once" invariant: an
     * account that got linked via {@code switch_tenant} (never having called {@code create_tenant}
     * at all) must still be rejected by {@code create_tenant} - the existing coverage only ever
     * tested the create-first ordering (mcp_plan.md Grooming #27).
     */
    @Test
    void createTenantIsRejectedForAnAccountThatFirstLinkedViaSwitchTenant() {
        TenantFixture existing = createRestTenant("Switch First Tenant");
        String username = "switch-first-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";
        restTemplate.postForEntity(
                "/api/v1/mcp-users/register",
                new RegisterMcpUserRequest(username, password, null),
                RegisterMcpUserResponse.class);
        client = buildClient(username, password);
        client.initialize();

        callTool(
                "switch_tenant",
                Map.of("tenantId", existing.tenantId().toString(), "apiKey", existing.apiKey()));

        McpSchema.CallToolResult createAfterSwitch =
                client.callTool(
                        new McpSchema.CallToolRequest(
                                "create_tenant", Map.of("name", "Should Never Exist")));
        assertThat(createAfterSwitch.isError()).isEqualTo(Boolean.TRUE);
        assertThat(((McpSchema.TextContent) createAfterSwitch.content().get(0)).text())
                .contains("already linked to a tenant");
        assertThat(mcpUserRepository.findByUsername(username).orElseThrow().getTenantId())
                .as("the failed create_tenant must not have moved the account off its tenant")
                .isEqualTo(existing.tenantId());
    }

    /**
     * The user's literal requirement (mcp_plan.md Grooming #26): "only 5 tenants per mcp_user
     * including create and switch tenants" - the cap counts both tools <em>combined</em>. The other
     * cap test reaches 5 via switches alone; this one proves the {@code create_tenant} contribution
     * genuinely occupies a slot: 1 create + 4 switches = 5, and a 6th distinct tenant is rejected.
     * Also asserts the history rows directly, including the created tenant's own row - the only
     * automated proof that {@code create_tenant} writes its history entry at all.
     */
    @Test
    void theFiveTenantCapCountsCreateTenantAndSwitchTenantCombined() {
        TenantFixture[] tenants = new TenantFixture[5];
        for (int i = 0; i < tenants.length; i++) {
            tenants[i] = createRestTenant("Combined Cap Tenant " + i);
        }
        String username = "combined-cap-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";
        restTemplate.postForEntity(
                "/api/v1/mcp-users/register",
                new RegisterMcpUserRequest(username, password, null),
                RegisterMcpUserResponse.class);
        client = buildClient(username, password);
        client.initialize();

        Map<String, Object> created = callTool("create_tenant", Map.of("name", "Created Slot One"));
        String createdApiKey = (String) created.get("apiKey");
        UUID createdTenantId =
                mcpUserRepository.findByUsername(username).orElseThrow().getTenantId();
        UUID mcpUserId = mcpUserRepository.findByUsername(username).orElseThrow().getId();
        assertThat(linkRepository.existsByMcpUserIdAndTenantId(mcpUserId, createdTenantId))
                .as("create_tenant must record its own history row - it occupies slot 1 of 5")
                .isTrue();

        for (int i = 0; i < 4; i++) {
            callTool(
                    "switch_tenant",
                    Map.of(
                            "tenantId",
                            tenants[i].tenantId().toString(),
                            "apiKey",
                            tenants[i].apiKey()));
        }
        assertThat(linkRepository.countByMcpUserId(mcpUserId)).isEqualTo(5);

        McpSchema.CallToolResult sixth =
                client.callTool(
                        new McpSchema.CallToolRequest(
                                "switch_tenant",
                                Map.of(
                                        "tenantId",
                                        tenants[4].tenantId().toString(),
                                        "apiKey",
                                        tenants[4].apiKey())));
        assertThat(sixth.isError()).isEqualTo(Boolean.TRUE);
        assertThat(((McpSchema.TextContent) sixth.content().get(0)).text())
                .contains("maximum of 5 tenants");

        // Even at the cap, switching back to the tenant this account originally *created* is free.
        Map<String, Object> revisitCreated =
                callTool(
                        "switch_tenant",
                        Map.of("tenantId", createdTenantId.toString(), "apiKey", createdApiKey));
        assertThat(revisitCreated.get("tenantId")).isEqualTo(createdTenantId.toString());
        assertThat(linkRepository.countByMcpUserId(mcpUserId)).isEqualTo(5);
    }

    @Test
    void switchTenantRejectsAnInvalidApiKeyWithACleanError() {
        TenantFixture tenantA = createRestTenant("Invalid Key Test Tenant");
        String username = "switch-invalid-key-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";
        restTemplate.postForEntity(
                "/api/v1/mcp-users/register",
                new RegisterMcpUserRequest(username, password, null),
                RegisterMcpUserResponse.class);
        client = buildClient(username, password);
        client.initialize();

        McpSchema.CallToolResult result =
                client.callTool(
                        new McpSchema.CallToolRequest(
                                "switch_tenant",
                                Map.of(
                                        "tenantId",
                                        tenantA.tenantId().toString(),
                                        "apiKey",
                                        "genuinely-wrong-key")));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertThat(text).contains("invalid API key");
    }

    /**
     * mcp_plan.md Grooming #26: "only 5 tenants per mcp_user including create and switch tenants" -
     * a 6th, never-before-touched tenant is rejected once the account has switched to 5 distinct
     * ones, but re-visiting one of the original 5 remains free even at the cap.
     */
    @Test
    void switchTenantEnforcesTheFiveDistinctTenantCapPerAccount() {
        TenantFixture[] tenants = new TenantFixture[6];
        for (int i = 0; i < tenants.length; i++) {
            tenants[i] = createRestTenant("Cap Test Tenant " + i);
        }
        String username = "switch-cap-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";
        restTemplate.postForEntity(
                "/api/v1/mcp-users/register",
                new RegisterMcpUserRequest(username, password, null),
                RegisterMcpUserResponse.class);
        client = buildClient(username, password);
        client.initialize();

        for (int i = 0; i < 5; i++) {
            Map<String, Object> result =
                    callTool(
                            "switch_tenant",
                            Map.of(
                                    "tenantId",
                                    tenants[i].tenantId().toString(),
                                    "apiKey",
                                    tenants[i].apiKey()));
            assertThat(result.get("tenantId")).isEqualTo(tenants[i].tenantId().toString());
        }

        // A 6th, brand-new tenant is rejected once the account has reached the cap.
        McpSchema.CallToolResult sixth =
                client.callTool(
                        new McpSchema.CallToolRequest(
                                "switch_tenant",
                                Map.of(
                                        "tenantId",
                                        tenants[5].tenantId().toString(),
                                        "apiKey",
                                        tenants[5].apiKey())));
        assertThat(sixth.isError()).isEqualTo(Boolean.TRUE);
        String sixthText = ((McpSchema.TextContent) sixth.content().get(0)).text();
        assertThat(sixthText).contains("maximum of 5 tenants");

        // Switching back to one of the original 5 still works fine at the cap.
        Map<String, Object> revisit =
                callTool(
                        "switch_tenant",
                        Map.of(
                                "tenantId",
                                tenants[2].tenantId().toString(),
                                "apiKey",
                                tenants[2].apiKey()));
        assertThat(revisit.get("tenantId")).isEqualTo(tenants[2].tenantId().toString());
    }

    @Test
    void switchTenantRejectsAValidKeyForADifferentTenantThanClaimed() {
        TenantFixture tenantA = createRestTenant("Mismatch Test Tenant A");
        TenantFixture tenantB = createRestTenant("Mismatch Test Tenant B");
        String username = "switch-mismatch-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";
        restTemplate.postForEntity(
                "/api/v1/mcp-users/register",
                new RegisterMcpUserRequest(username, password, null),
                RegisterMcpUserResponse.class);
        client = buildClient(username, password);
        client.initialize();

        // Claim tenant A's id but present tenant B's real, valid API key.
        McpSchema.CallToolResult result =
                client.callTool(
                        new McpSchema.CallToolRequest(
                                "switch_tenant",
                                Map.of(
                                        "tenantId", tenantA.tenantId().toString(),
                                        "apiKey", tenantB.apiKey())));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertThat(text).contains("does not belong to the given tenantId");
        assertThat(mcpUserRepository.findByUsername(username).orElseThrow().getTenantId()).isNull();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(String name, Map<String, Object> arguments) {
        McpSchema.CallToolResult result =
                client.callTool(new McpSchema.CallToolRequest(name, arguments));
        assertThat(result.isError())
                .as("tool call to " + name + " should not error: " + result.content())
                .isNotEqualTo(Boolean.TRUE);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(text, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
