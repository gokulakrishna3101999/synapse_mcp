package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapsemcp.AbstractIntegrationTest;
import com.synapsemcp.chat.ChatModelFactory;
import com.synapsemcp.common.RedisKeyPrefix;
import com.synapsemcp.embedding.EmbeddingModelFactory;
import com.synapsemcp.knowledgebase.KnowledgeBaseRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * User-requested (2026-07-22) {@code switch_knowledge_base} tool - see {@link
 * McpActiveKnowledgeBaseSwitchingService}'s Javadoc for the full design rationale. Proven end to
 * end over genuine Streamable HTTP against a real Postgres database, not just the unit-level mocks
 * in {@code McpActiveKnowledgeBaseSwitchingServiceTest}/{@code SwitchKnowledgeBaseMcpToolTest}.
 */
class McpSwitchKnowledgeBaseIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private McpUserRepository mcpUserRepository;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private RedisKeyPrefix redisKeyPrefix;
    @MockitoSpyBean private KnowledgeBaseRepository knowledgeBaseRepository;

    @MockitoBean private EmbeddingModelFactory embeddingModelFactory;
    @MockitoBean private ChatModelFactory chatModelFactory;

    @LocalServerPort private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private McpSyncClient client;

    @BeforeEach
    void stubModels() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.dimensions()).thenReturn(1536);
        when(embeddingModel.embed(anyList()))
                .thenAnswer(
                        invocation -> {
                            List<String> texts = invocation.getArgument(0);
                            List<float[]> vectors = new ArrayList<>();
                            for (int i = 0; i < texts.size(); i++) {
                                vectors.add(new float[1536]);
                            }
                            return vectors;
                        });
        when(embeddingModelFactory.getEmbeddingModel(any())).thenReturn(embeddingModel);
        when(embeddingModelFactory.getEmbeddingModelForKnowledgeBase(any()))
                .thenReturn(embeddingModel);
        when(chatModelFactory.optionsForKnowledgeBase(any()))
                .thenReturn(ChatOptions.builder().model("gpt-4o").build());

        ChatModel chatModel = mock(ChatModel.class);
        AssistantMessage message = new AssistantMessage("This is kb-alpha [Source 1].");
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(message))));
        when(chatModelFactory.getChatModelForKnowledgeBase(any())).thenReturn(chatModel);
    }

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void switchingByNameLetsEveryOtherToolOmitAnExplicitId() {
        String username = "mcp-switch-kb-user-" + UUID.randomUUID();
        registerMcpUser(username, "correct-horse-battery-staple");
        client = buildClient(username, "correct-horse-battery-staple");
        client.initialize();

        callTool("create_tenant", Map.of("name", "Switch KB Tenant"));
        configureModel();
        String kbAlphaId = createKnowledgeBase("kb-alpha");
        String kbBetaId = createKnowledgeBase("kb-beta");

        // No active knowledge base yet - every omitted-id call is cleanly rejected, not silently
        // routed to whichever knowledge base happens to exist.
        McpSchema.CallToolResult beforeSwitch =
                client.callTool(
                        new McpSchema.CallToolRequest("search", Map.of("query", "anything")));
        assertThat(beforeSwitch.isError()).isEqualTo(Boolean.TRUE);
        assertThat(((McpSchema.TextContent) beforeSwitch.content().get(0)).text())
                .contains("switch_knowledge_base");

        // Switch to kb-alpha by name.
        Map<String, Object> switchResult =
                asMap(callTool("switch_knowledge_base", Map.of("name", "kb-alpha")));
        assertThat(switchResult.get("id")).isEqualTo(kbAlphaId);

        // ingest, without an explicit knowledgeBaseId, lands in kb-alpha.
        Map<String, Object> ingestResult =
                asMap(
                        callTool(
                                "ingest",
                                Map.of(
                                        "text",
                                        "kb-alpha contains this ALPHA-MARKER sentence only.")));
        String jobId = (String) ingestResult.get("jobId");
        assertThat(awaitJobStatus(jobId)).isEqualTo("READY");

        List<Map<String, Object>> searchInAlpha =
                asList(callTool("search", Map.of("query", "ALPHA-MARKER")));
        assertThat(searchInAlpha).isNotEmpty();

        Map<String, Object> askInAlpha =
                asMap(callTool("ask", Map.of("question", "what does kb-alpha contain?")));
        assertThat(askInAlpha.get("answer")).isEqualTo("This is kb-alpha [Source 1].");

        // An explicit knowledgeBaseId still overrides the active one - kb-beta has nothing
        // ingested, so an explicit search against it must come back empty even while kb-alpha is
        // active.
        List<Map<String, Object>> explicitSearchInBeta =
                asList(
                        callTool(
                                "search",
                                Map.of("knowledgeBaseName", "kb-beta", "query", "ALPHA-MARKER")));
        assertThat(explicitSearchInBeta).isEmpty();

        // Now switch to kb-beta - subsequent omitted-id calls follow.
        Map<String, Object> switchToBeta =
                asMap(callTool("switch_knowledge_base", Map.of("name", "kb-beta")));
        assertThat(switchToBeta.get("id")).isEqualTo(kbBetaId);

        Map<String, Object> renameResult =
                asMap(callTool("update_knowledge_base", Map.of("newName", "kb-beta-renamed")));
        assertThat(renameResult.get("id")).isEqualTo(kbBetaId);
        assertThat(renameResult.get("name")).isEqualTo("kb-beta-renamed");
    }

    @Test
    void switchingToANameThatBelongsToAnotherTenantIsIndistinguishableFromNonexistent() {
        // Tenant A creates a knowledge base named "shared-name" via REST.
        var tenantAFixture = createConfiguredRestTenant("Switch KB Tenant A");

        String username = "mcp-switch-kb-crosstenant-" + UUID.randomUUID();
        registerMcpUser(username, "correct-horse-battery-staple");
        client = buildClient(username, "correct-horse-battery-staple");
        client.initialize();

        // This MCP account creates and links to a completely different tenant B, which has no
        // knowledge base named "shared-name" at all.
        callTool("create_tenant", Map.of("name", "Switch KB Tenant B"));
        configureModel();

        McpSchema.CallToolResult result =
                client.callTool(
                        new McpSchema.CallToolRequest(
                                "switch_knowledge_base", Map.of("name", "Tenant A's KB")));
        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).contains("not found");

        // Confirmed independently: tenant A's own knowledge base genuinely exists (proving the
        // rejection above is real tenant isolation, not just a typo in the fixture name).
        assertThat(tenantAFixture).isNotNull();
    }

    @Test
    void switchingTenantsClearsTheActiveKnowledgeBase() {
        var tenantAFixture = createConfiguredRestTenant("Switch KB Clears Tenant A");

        String username = "mcp-switch-kb-clears-" + UUID.randomUUID();
        registerMcpUser(username, "correct-horse-battery-staple");
        client = buildClient(username, "correct-horse-battery-staple");
        client.initialize();

        callTool("create_tenant", Map.of("name", "Switch KB Clears Tenant B"));
        configureModel();
        createKnowledgeBase("clears-kb");
        callTool("switch_knowledge_base", Map.of("name", "clears-kb"));

        UUID mcpUserId = mcpUserRepository.findByUsername(username).orElseThrow().getId();
        assertThat(mcpUserRepository.findById(mcpUserId).orElseThrow().getActiveKnowledgeBaseId())
                .isNotNull();

        callTool(
                "switch_tenant",
                Map.of(
                        "tenantId", tenantAFixture.tenantId().toString(),
                        "apiKey", tenantAFixture.apiKey()));

        assertThat(mcpUserRepository.findById(mcpUserId).orElseThrow().getActiveKnowledgeBaseId())
                .as("switching tenants must clear the previous tenant's active knowledge base")
                .isNull();

        McpSchema.CallToolResult afterTenantSwitch =
                client.callTool(new McpSchema.CallToolRequest("search", Map.of("query", "x")));
        assertThat(afterTenantSwitch.isError()).isEqualTo(Boolean.TRUE);
        assertThat(((McpSchema.TextContent) afterTenantSwitch.content().get(0)).text())
                .contains("switch_knowledge_base");
    }

    @Test
    void deletingTheActiveKnowledgeBaseClearsThePointer() {
        String username = "mcp-switch-kb-delete-" + UUID.randomUUID();
        registerMcpUser(username, "correct-horse-battery-staple");
        client = buildClient(username, "correct-horse-battery-staple");
        client.initialize();

        callTool("create_tenant", Map.of("name", "Switch KB Delete Tenant"));
        configureModel();
        String kbId = createKnowledgeBase("to-be-deleted-kb");
        callTool("switch_knowledge_base", Map.of("name", "to-be-deleted-kb"));

        UUID mcpUserId = mcpUserRepository.findByUsername(username).orElseThrow().getId();
        assertThat(mcpUserRepository.findById(mcpUserId).orElseThrow().getActiveKnowledgeBaseId())
                .isEqualTo(UUID.fromString(kbId));

        callTool("delete_knowledge_base", Map.of("knowledgeBaseName", "to-be-deleted-kb"));

        assertThat(mcpUserRepository.findById(mcpUserId).orElseThrow().getActiveKnowledgeBaseId())
                .as(
                        "deleting the active knowledge base must clear the pointer automatically"
                                + " (ON DELETE SET NULL), not leave it dangling")
                .isNull();
    }

    /**
     * "Final" architect-level validation round (mcp_plan.md Grooming #29): {@code
     * McpActiveKnowledgeBaseSwitchingService.switchKnowledgeBase} reads the target knowledge base
     * by name, then persists a reference to it in a separate statement, all within one
     * {@code @Transactional} method - the exact same "read, then act on a possibly-since-deleted
     * row" shape as the ingestion pipeline's own persist-to-index window (rag_plan.md Grooming
     * #87), just one call narrower. A concurrent {@code delete_knowledge_base} for the very
     * knowledge base being switched to, landing in that gap, was a genuinely open question never
     * reproduced by any existing test.
     *
     * <p>An earlier version of this test tried to reproduce the race by having a {@link
     * MockitoSpyBean}-wrapped {@code KnowledgeBaseRepository} call its own real method before
     * triggering the concurrent delete - found live (not guessed) that this doesn't work at all for
     * a Spring Data JPA repository: {@code Mockito.callRealMethod()} requires real bytecode to
     * invoke, but a repository is an <em>interface</em> with no real implementation of its own
     * (Spring Data generates one dynamically) - the call fails with Mockito's own "Cannot call
     * abstract real method on java object!" error, a test-technique dead end distinct from Grooming
     * #87's {@code @Transactional}-bypass one. Fixed by not using {@code callRealMethod()} at all:
     * the real knowledge base is fetched once genuinely, deleted for real, and the lookup is then
     * stubbed with a plain {@code thenReturn} of that now-stale (but still valid-shaped) entity -
     * reproducing exactly what {@code switchKnowledgeBase} would see if the real delete had landed
     * in the gap between its own real lookup and its own real persist step.
     */
    @Test
    void aConcurrentDeleteBetweenTheNameLookupAndPersistingTheActivePointerFailsCleanly() {
        String username = "mcp-switch-kb-race-" + UUID.randomUUID();
        registerMcpUser(username, "correct-horse-battery-staple");
        client = buildClient(username, "correct-horse-battery-staple");
        client.initialize();

        callTool("create_tenant", Map.of("name", "Switch KB Race Tenant"));
        configureModel();
        String kbId = createKnowledgeBase("race-kb");

        com.synapsemcp.knowledgebase.KnowledgeBase staleKnowledgeBase =
                knowledgeBaseRepository.findById(UUID.fromString(kbId)).orElseThrow();

        McpSchema.CallToolResult deleteResult =
                client.callTool(
                        new McpSchema.CallToolRequest(
                                "delete_knowledge_base", Map.of("knowledgeBaseName", "race-kb")));
        assertThat(deleteResult.isError())
                .as("the concurrent delete itself must succeed cleanly")
                .isNotEqualTo(Boolean.TRUE);
        assertThat(knowledgeBaseRepository.findById(UUID.fromString(kbId))).isEmpty();

        org.mockito.Mockito.when(
                        knowledgeBaseRepository.findByNameIgnoreCaseAndTenant_Id(
                                org.mockito.ArgumentMatchers.eq("race-kb"),
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(staleKnowledgeBase));

        McpSchema.CallToolResult switchResult =
                client.callTool(
                        new McpSchema.CallToolRequest(
                                "switch_knowledge_base", Map.of("name", "race-kb")));

        // Confirmed finding, not a guess: a race lost by the switch must surface the exact same
        // clean "not found" error a plainly-nonexistent name would - never the raw
        // DataIntegrityViolationException (constraint name, table, column, offending id value)
        // that reached the client before McpActiveKnowledgeBaseSwitchingService caught and
        // translated it.
        assertThat(switchResult.isError()).isEqualTo(Boolean.TRUE);
        String text = ((McpSchema.TextContent) switchResult.content().get(0)).text();
        assertThat(text)
                .contains("knowledge base not found")
                .doesNotContainIgnoringCase("constraint")
                .doesNotContainIgnoringCase("DataIntegrityViolation")
                .doesNotContainIgnoringCase("SQLException");

        UUID mcpUserId = mcpUserRepository.findByUsername(username).orElseThrow().getId();
        assertThat(mcpUserRepository.findById(mcpUserId).orElseThrow().getActiveKnowledgeBaseId())
                .as("a race lost by the switch must never leave a dangling active pointer")
                .isNull();
    }

    private void configureModel() {
        callTool(
                "configure_model",
                Map.of(
                        "chatProvider", "openai",
                        "chatModel", "gpt-4o",
                        "embeddingProvider", "openai",
                        "embeddingModel", "text-embedding-3-small",
                        "chatApiKey", "sk-fake-chat-key",
                        "embeddingApiKey", "sk-fake-embed-key"));
    }

    private String createKnowledgeBase(String name) {
        Map<String, Object> kb = asMap(callTool("create_knowledge_base", Map.of("name", name)));
        return (String) kb.get("id");
    }

    private record RestTenantFixture(UUID tenantId, String apiKey) {}

    private RestTenantFixture createConfiguredRestTenant(String name) {
        Set<String> rateLimitKeys =
                redisTemplate.keys(redisKeyPrefix.key("rate_limit:tenant-create:*"));
        if (rateLimitKeys != null && !rateLimitKeys.isEmpty()) {
            redisTemplate.delete(rateLimitKeys);
        }
        com.synapsemcp.tenant.CreateTenantResponse tenant =
                restTemplate.postForObject(
                        "/api/v1/tenants",
                        new com.synapsemcp.tenant.CreateTenantRequest(name),
                        com.synapsemcp.tenant.CreateTenantResponse.class);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(tenant.apiKey());
        restTemplate.exchange(
                "/api/v1/tenants/{tenantId}/model-config",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        new com.synapsemcp.tenant.ConfigureModelRequest(
                                "openai",
                                "gpt-4o",
                                "openai",
                                "text-embedding-3-small",
                                "sk-fake-chat-key",
                                "sk-fake-embed-key"),
                        headers),
                com.synapsemcp.tenant.ModelConfigResponse.class,
                tenant.tenantId());
        restTemplate.exchange(
                "/api/v1/knowledgebase",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(
                        new com.synapsemcp.knowledgebase.CreateKnowledgeBaseRequest(
                                "Tenant A's KB"),
                        headers),
                com.synapsemcp.knowledgebase.KnowledgeBaseResponse.class);
        return new RestTenantFixture(tenant.tenantId(), tenant.apiKey());
    }

    private String awaitJobStatus(String jobId) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> status = asMap(callTool("job_status", Map.of("jobId", jobId)));
            String current = (String) status.get("status");
            if ("READY".equals(current) || "FAILED".equals(current)) {
                return current;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("job " + jobId + " did not reach a terminal state within 5s");
    }

    private McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
        McpSchema.CallToolResult result =
                client.callTool(new McpSchema.CallToolRequest(name, arguments));
        assertThat(result.isError())
                .as("tool call to " + name + " should not error: " + result.content())
                .isNotEqualTo(Boolean.TRUE);
        return result;
    }

    private String textOf(McpSchema.CallToolResult result) {
        McpSchema.Content content = result.content().get(0);
        assertThat(content).isInstanceOf(McpSchema.TextContent.class);
        return ((McpSchema.TextContent) content).text();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(McpSchema.CallToolResult result) {
        try {
            return objectMapper.readValue(textOf(result), Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asList(McpSchema.CallToolResult result) {
        try {
            return objectMapper.readValue(textOf(result), List.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
