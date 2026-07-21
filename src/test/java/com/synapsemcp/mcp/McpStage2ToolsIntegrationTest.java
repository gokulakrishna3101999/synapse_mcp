package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapsemcp.AbstractIntegrationTest;
import com.synapsemcp.chat.ChatModelFactory;
import com.synapsemcp.embedding.EmbeddingModelFactory;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * mcp_plan.md Stage 2: exercises all twelve remaining tools end-to-end over a real Streamable HTTP
 * MCP connection against a seeded knowledge base - the "exercising every tool against a seeded KB"
 * integration test left explicitly open since Stage 1. No live chat/embedding provider is available
 * in this environment (same recurring blocker as every REST integration test) - both {@link
 * EmbeddingModelFactory} and {@link ChatModelFactory} are replaced with {@code @MockitoBean}s.
 *
 * <p>Tool results are read from {@link McpSchema.CallToolResult#content()}'s JSON text, not {@link
 * McpSchema.CallToolResult#structuredContent()} - confirmed via decompiling {@code
 * AbstractMcpToolMethodCallback#convertValueToCallToolResult} that {@code structuredContent()} is
 * only populated when a tool's {@code @McpTool(generateOutputSchema = true)}, which none of these
 * tools set; the default path serializes the return value as a single JSON {@code TextContent}
 * instead.
 */
class McpStage2ToolsIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private McpUserRepository mcpUserRepository;

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
        AssistantMessage message =
                new AssistantMessage("Synapse MCP is a RAG platform [Source 1].");
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
    void exercisesAllTwelveStage2ToolsEndToEndAgainstASeededKnowledgeBase() {
        String username = "mcp-stage2-user-" + UUID.randomUUID();
        registerMcpUser(username, "correct-horse-battery-staple");
        client = buildClient(username, "correct-horse-battery-staple");
        client.initialize();

        callTool("create_tenant", Map.of("name", "Stage 2 Tenant"));
        UUID tenantId = mcpUserRepository.findByUsername(username).orElseThrow().getTenantId();
        assertThat(tenantId).isNotNull();

        callTool(
                "configure_model",
                Map.of(
                        "chatProvider", "openai",
                        "chatModel", "gpt-4o",
                        "embeddingProvider", "openai",
                        "embeddingModel", "text-embedding-3-small",
                        "chatApiKey", "sk-fake-chat-key",
                        "embeddingApiKey", "sk-fake-embed-key"));

        Map<String, Object> kb =
                asMap(callTool("create_knowledge_base", Map.of("name", "stage2-kb")));
        String kbId = (String) kb.get("id");
        assertThat(kbId).isNotNull();

        Map<String, Object> renamedKb =
                asMap(
                        callTool(
                                "update_knowledge_base",
                                Map.of(
                                        "knowledgeBaseName",
                                        "stage2-kb",
                                        "newName",
                                        "stage2-kb-renamed")));
        assertThat(renamedKb.get("name")).isEqualTo("stage2-kb-renamed");

        Map<String, Object> ingestResult =
                asMap(
                        callTool(
                                "ingest",
                                Map.of(
                                        "knowledgeBaseName",
                                        "stage2-kb-renamed",
                                        "text",
                                        "Synapse MCP is a retrieval-augmented generation platform"
                                                + " used for testing knowledge bases.")));
        String jobId = (String) ingestResult.get("jobId");
        String documentId = (String) ingestResult.get("documentId");
        assertThat(jobId).isNotNull();

        String finalStatus = awaitJobStatus(jobId);
        assertThat(finalStatus).isEqualTo("READY");

        Map<String, Object> documentStatus =
                asMap(callTool("get_document_status", Map.of("documentId", documentId)));
        assertThat(documentStatus.get("status")).isEqualTo("READY");

        List<Map<String, Object>> searchResults =
                asList(
                        callTool(
                                "search",
                                Map.of(
                                        "knowledgeBaseName",
                                        "stage2-kb-renamed",
                                        "query",
                                        "what is synapse mcp?")));
        assertThat(searchResults).isNotEmpty();
        String chunkId = (String) searchResults.get(0).get("chunkId");
        assertThat(chunkId).isNotNull();

        Map<String, Object> askResult =
                asMap(
                        callTool(
                                "ask",
                                Map.of(
                                        "knowledgeBaseName",
                                        "stage2-kb-renamed",
                                        "question",
                                        "what is synapse mcp?")));
        assertThat(askResult.get("answer")).isEqualTo("Synapse MCP is a RAG platform [Source 1].");

        Map<String, Object> evaluateResult =
                asMap(
                        callTool(
                                "evaluate",
                                Map.of(
                                        "knowledgeBaseName",
                                        "stage2-kb-renamed",
                                        "queries",
                                        List.of(
                                                Map.of(
                                                        "query",
                                                        "what is synapse mcp?",
                                                        "expectedChunkIds",
                                                        List.of(chunkId))))));
        assertThat(((Number) evaluateResult.get("meanReciprocalRank")).doubleValue())
                .isGreaterThan(0.0);

        List<Map<String, Object>> knowledgeBases =
                asList(callTool("list_knowledge_bases", Map.of()));
        Map<String, Object> listedKb =
                knowledgeBases.stream()
                        .filter(k -> kbId.equals(k.get("id")))
                        .findFirst()
                        .orElseThrow();
        Map<String, Object> listedSummary =
                (Map<String, Object>) listedKb.get("documentStatusSummary");
        assertThat(((Number) listedSummary.get("ready")).longValue()).isEqualTo(1L);

        Map<String, Object> tenantDetail = asMap(callTool("get_tenant", Map.of()));
        assertThat(((Number) tenantDetail.get("knowledgeBaseCount")).intValue()).isEqualTo(1);
        Map<String, Object> tenantSummary =
                (Map<String, Object>) tenantDetail.get("documentStatusSummary");
        assertThat(((Number) tenantSummary.get("ready")).longValue()).isEqualTo(1L);

        Map<String, Object> deleteResult =
                asMap(
                        callTool(
                                "delete_knowledge_base",
                                Map.of("knowledgeBaseName", "stage2-kb-renamed")));
        assertThat(deleteResult.get("deleted")).isEqualTo(Boolean.TRUE);

        List<Map<String, Object>> knowledgeBasesAfterDelete =
                asList(callTool("list_knowledge_bases", Map.of()));
        assertThat(knowledgeBasesAfterDelete).noneMatch(k -> kbId.equals(k.get("id")));
    }

    /**
     * mcp_plan.md Stage 2, Grooming #19: {@code RequestBodySizeLimitFilter}'s {@code /mcp}
     * exemption is unit-tested against a {@code MockHttpServletRequest}, but never against a
     * genuine HTTP request body over the wire - a real {@code Content-Length}/chunked-transfer body
     * over a real socket is exactly the case that filter's own Javadoc says a header-only check
     * could miss. This sends a real ~2MB raw-text {@code ingest} call (comfortably over the
     * filter's 1MB default, comfortably under the 20MB cap) through the real MCP Java SDK client
     * over genuine Streamable HTTP, and confirms it is not rejected before ever reaching {@code
     * IngestMcpTool}.
     */
    @Test
    void ingestSucceedsWithABodyOverTheDefaultOneMegabyteRequestSizeLimit() {
        String username = "mcp-stage2-bigbody-user-" + UUID.randomUUID();
        registerMcpUser(username, "correct-horse-battery-staple");
        client = buildClient(username, "correct-horse-battery-staple");
        client.initialize();

        callTool("create_tenant", Map.of("name", "Stage 2 Big Body Tenant"));
        callTool(
                "configure_model",
                Map.of(
                        "chatProvider", "openai",
                        "chatModel", "gpt-4o",
                        "embeddingProvider", "openai",
                        "embeddingModel", "text-embedding-3-small",
                        "chatApiKey", "sk-fake-chat-key",
                        "embeddingApiKey", "sk-fake-embed-key"));
        Map<String, Object> kb =
                asMap(callTool("create_knowledge_base", Map.of("name", "big-body-kb")));
        String kbId = (String) kb.get("id");

        String bigText = "word ".repeat(2 * 1024 * 1024 / 5);
        Map<String, Object> ingestResult =
                asMap(
                        callTool(
                                "ingest",
                                Map.of("knowledgeBaseName", "big-body-kb", "text", bigText)));
        String jobId = (String) ingestResult.get("jobId");
        assertThat(jobId).isNotNull();
        assertThat(awaitJobStatus(jobId)).isEqualTo("READY");
    }

    /**
     * Regression protection for the 20MB cap on the MCP transport (mcp_plan.md Grooming #19(6),
     * re-confirmed user-requested 2026-07-21 as "20MB despite the file format", Grooming #26):
     * {@code IngestMcpTool}'s own explicit byte-length check on the <em>decoded</em> content (there
     * is no multipart resolver on this transport to enforce it) must reject one byte over the cap
     * with a clean tool-level error, before any format-specific code (Tika detection) ever runs -
     * previously only ever verified live on disposable instances. The at-limit acceptance side
     * stays live-verified only, for the same pipeline-cost reason as the REST twin ({@code
     * DocumentControllerIntegrationTest#rejectsAFileOverTheTwentyMegabyteCapWith413BeforeAnyFormatDetection}).
     */
    @Test
    void ingestRejectsDecodedContentOverTheTwentyMegabyteCapWithACleanError() {
        String username = "mcp-stage2-overcap-user-" + UUID.randomUUID();
        registerMcpUser(username, "correct-horse-battery-staple");
        client = buildClient(username, "correct-horse-battery-staple");
        client.initialize();

        callTool("create_tenant", Map.of("name", "Stage 2 Over Cap Tenant"));
        callTool(
                "configure_model",
                Map.of(
                        "chatProvider", "openai",
                        "chatModel", "gpt-4o",
                        "embeddingProvider", "openai",
                        "embeddingModel", "text-embedding-3-small",
                        "chatApiKey", "sk-fake-chat-key",
                        "embeddingApiKey", "sk-fake-embed-key"));
        Map<String, Object> kb =
                asMap(callTool("create_knowledge_base", Map.of("name", "over-cap-kb")));
        String kbId = (String) kb.get("id");

        byte[] oneByteOverTwentyMegabytes = new byte[20 * 1024 * 1024 + 1];
        java.util.Arrays.fill(oneByteOverTwentyMegabytes, (byte) 'A');
        String contentBase64 = Base64.getEncoder().encodeToString(oneByteOverTwentyMegabytes);

        McpSchema.CallToolResult result =
                client.callTool(
                        new McpSchema.CallToolRequest(
                                "ingest",
                                Map.of(
                                        "knowledgeBaseName", "over-cap-kb",
                                        "filename", "huge.txt",
                                        "contentBase64", contentBase64)));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(((McpSchema.TextContent) result.content().get(0)).text())
                .contains("exceeds maximum allowed size");
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
