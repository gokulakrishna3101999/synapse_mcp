package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapsemcp.AbstractIntegrationTest;
import com.synapsemcp.chat.ChatModelFactory;
import com.synapsemcp.document.UploadDocumentResponse;
import com.synapsemcp.embedding.EmbeddingModelFactory;
import com.synapsemcp.knowledgebase.CreateKnowledgeBaseRequest;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Phase 1 (rag_plan.md) + Phase 2 (mcp_plan.md) combined, genuinely cross-transport end-to-end test
 * - found missing during a thorough validation pass: every existing MCP integration test creates
 * and consumes its data exclusively through MCP (including {@code create_tenant} itself), and every
 * REST integration test never touches {@code /mcp} at all. Nothing proved Grooming #2's own
 * architectural claim - that MCP tools and REST controllers are "equal-peer consumers" of the
 * identical domain services - actually holds for data crossing between the two: a tenant/knowledge
 * base created via one transport must be fully readable, searchable, and mutable via the other,
 * since both ultimately hit the same {@code TenantService}/{@code KnowledgeBaseService}/etc.
 * instances against the same database rows, not two independently-behaving code paths that happen
 * to look similar.
 *
 * <p>No live chat/embedding provider is available in this environment (same recurring blocker as
 * every other REST/MCP integration test) - both {@link EmbeddingModelFactory} and {@link
 * ChatModelFactory} are replaced with {@code @MockitoBean}s.
 */
class CombinedRestAndMcpIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;

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
        AssistantMessage message = new AssistantMessage("Combined answer [Source 1].");
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
    void dataCreatedThroughEitherTransportIsFullyVisibleAndUsableThroughTheOther()
            throws Exception {
        // --- 1. Tenant + model config created via REST -------------------------------------
        CreateTenantResponse tenant =
                restTemplate.postForObject(
                        "/api/v1/tenants",
                        new CreateTenantRequest("Combined REST+MCP Tenant"),
                        CreateTenantResponse.class);
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
                        bearerHeaders(tenant.apiKey())),
                ModelConfigResponse.class,
                tenant.tenantId());

        // --- 2. An MCP account links directly to that same REST-created tenant -------------
        String username = "combined-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";
        ResponseEntity<RegisterMcpUserResponse> registration =
                restTemplate.postForEntity(
                        "/api/v1/mcp-users/register",
                        new RegisterMcpUserRequest(username, password, tenant.apiKey()),
                        RegisterMcpUserResponse.class);
        assertThat(registration.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registration.getBody().tenantId())
                .as("registering with an existing REST tenant's API key must link immediately")
                .isEqualTo(tenant.tenantId());

        client = buildMcpClient(username, password);
        client.initialize();

        // --- 3. Knowledge base #1 created + ingested entirely via MCP ----------------------
        Map<String, Object> kb1 =
                callTool("create_knowledge_base", Map.of("name", "mcp-origin-kb"));
        String kb1Id = (String) kb1.get("id");

        Map<String, Object> ingestResult =
                callTool(
                        "ingest",
                        Map.of(
                                "knowledgeBaseId",
                                kb1Id,
                                "text",
                                "The MCP-ORIGIN-MARKER document was created entirely through the"
                                        + " MCP transport, never touching the REST API."));
        String jobId = (String) ingestResult.get("jobId");
        awaitMcpJobReady(jobId);

        // --- 4. That MCP-created document is fully visible and searchable via REST ---------
        ResponseEntity<Object[]> restSearchOfMcpData =
                restTemplate.exchange(
                        "/api/v1/knowledgebase/{id}/search",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of("query", "MCP-ORIGIN-MARKER"),
                                bearerHeaders(tenant.apiKey())),
                        Object[].class,
                        kb1Id);
        assertThat(restSearchOfMcpData.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restSearchOfMcpData.getBody())
                .as("a document ingested via MCP must be findable via the REST /search endpoint")
                .isNotEmpty();

        ResponseEntity<Map> restAskAboutMcpData =
                restTemplate.exchange(
                        "/api/v1/knowledgebase/{id}/ask",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of("question", "What is the MCP-ORIGIN-MARKER document about?"),
                                bearerHeaders(tenant.apiKey())),
                        Map.class,
                        kb1Id);
        assertThat(restAskAboutMcpData.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) restAskAboutMcpData.getBody().get("citations"))
                .as("REST /ask must be able to cite a chunk that was only ever ingested via MCP")
                .isNotEmpty();

        // --- 5. Knowledge base #2 created + uploaded entirely via REST ----------------------
        ResponseEntity<KnowledgeBaseResponse> kb2Response =
                restTemplate.exchange(
                        "/api/v1/knowledgebase",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new CreateKnowledgeBaseRequest("rest-origin-kb"),
                                bearerHeaders(tenant.apiKey())),
                        KnowledgeBaseResponse.class);
        UUID kb2Id = kb2Response.getBody().id();

        MultiValueMap<String, Object> uploadBody = new LinkedMultiValueMap<>();
        uploadBody.add(
                "file",
                new ByteArrayResource(
                        ("The REST-ORIGIN-MARKER document was uploaded entirely through the REST"
                                        + " API, never touching the MCP transport.")
                                .getBytes(StandardCharsets.UTF_8)) {
                    @Override
                    public String getFilename() {
                        return "rest-origin.txt";
                    }
                });
        HttpHeaders uploadHeaders = bearerHeaders(tenant.apiKey());
        uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<UploadDocumentResponse> uploadResponse =
                restTemplate.exchange(
                        "/api/v1/knowledgebase/{id}/documents",
                        HttpMethod.POST,
                        new HttpEntity<>(uploadBody, uploadHeaders),
                        UploadDocumentResponse.class,
                        kb2Id);
        awaitRestJobReady(tenant.apiKey(), uploadResponse.getBody().jobId());

        // --- 6. That REST-uploaded document is fully visible and searchable via MCP ---------
        List<Map<String, Object>> mcpSearchOfRestData =
                (List<Map<String, Object>>)
                        (List<?>)
                                callToolList(
                                        "search",
                                        Map.of(
                                                "knowledgeBaseId",
                                                kb2Id.toString(),
                                                "query",
                                                "REST-ORIGIN-MARKER"));
        assertThat(mcpSearchOfRestData)
                .as("a document uploaded via REST must be findable via the MCP search tool")
                .isNotEmpty();

        // --- 7. Both knowledge bases are visible through both transports, in agreement ------
        List<Map<String, Object>> mcpKbList = callToolList("list_knowledge_bases", Map.of());
        assertThat(mcpKbList.stream().map(kb -> (String) kb.get("id")))
                .as(
                        "list_knowledge_bases (MCP) must show both the MCP-created and REST-created KBs")
                .contains(kb1Id, kb2Id.toString());

        ResponseEntity<KnowledgeBaseResponse[]> restKbList =
                restTemplate.exchange(
                        "/api/v1/knowledgebase",
                        HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders(tenant.apiKey())),
                        KnowledgeBaseResponse[].class);
        assertThat(List.of(restKbList.getBody()))
                .extracting(KnowledgeBaseResponse::id)
                .as("GET /knowledgebase (REST) must agree with MCP's own view of the same tenant")
                .contains(UUID.fromString(kb1Id), kb2Id);

        Map<String, Object> tenantDetail = callTool("get_tenant", Map.of());
        assertThat(((Number) tenantDetail.get("knowledgeBaseCount")).intValue()).isEqualTo(2);
        Map<String, Object> tenantSummary =
                (Map<String, Object>) tenantDetail.get("documentStatusSummary");
        assertThat(((Number) tenantSummary.get("ready")).longValue())
                .as(
                        "get_tenant's rollup (MCP) must count both documents, regardless of which"
                                + " transport ingested each one")
                .isEqualTo(2L);

        // --- 8. Deleting one KB via MCP is immediately reflected in REST's own view ---------
        callTool("delete_knowledge_base", Map.of("knowledgeBaseId", kb1Id));

        ResponseEntity<KnowledgeBaseResponse[]> restKbListAfterMcpDelete =
                restTemplate.exchange(
                        "/api/v1/knowledgebase",
                        HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders(tenant.apiKey())),
                        KnowledgeBaseResponse[].class);
        assertThat(List.of(restKbListAfterMcpDelete.getBody()))
                .extracting(KnowledgeBaseResponse::id)
                .as("a knowledge base deleted via MCP must disappear from REST's own listing too")
                .containsExactly(kb2Id);
    }

    private void awaitMcpJobReady(String jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> status = callTool("job_status", Map.of("jobId", jobId));
            String current = (String) status.get("status");
            if ("READY".equals(current) || "FAILED".equals(current)) {
                assertThat(current).isEqualTo("READY");
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("MCP-ingested job " + jobId + " did not reach READY within 5s");
    }

    private void awaitRestJobReady(String apiKey, UUID jobId) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<Map> response =
                    restTemplate.exchange(
                            "/api/v1/jobs/{jobId}",
                            HttpMethod.GET,
                            new HttpEntity<>(bearerHeaders(apiKey)),
                            Map.class,
                            jobId);
            String status = (String) response.getBody().get("status");
            if ("READY".equals(status) || "FAILED".equals(status)) {
                assertThat(status).isEqualTo("READY");
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("REST-uploaded job " + jobId + " did not reach READY within 5s");
    }

    private HttpHeaders bearerHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        return headers;
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
            return objectMapper.readValue(text, Map.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> callToolList(String name, Map<String, Object> arguments) {
        McpSchema.CallToolResult result =
                client.callTool(new McpSchema.CallToolRequest(name, arguments));
        assertThat(result.isError())
                .as("tool call to " + name + " should not error: " + result.content())
                .isNotEqualTo(Boolean.TRUE);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        try {
            return objectMapper.readValue(text, List.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private McpSyncClient buildMcpClient(String username, String password) {
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
