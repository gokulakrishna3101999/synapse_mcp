package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
 * mcp_plan.md Stage 3: closes a real coverage gap found during a thorough validation pass - {@code
 * RateLimitIntegrationTest} only ever drove a rate-limit denial over REST; nothing automated
 * confirmed the identical {@code RateLimitAspect}/{@code ProviderRateLimiter} denial surfaces as a
 * clean MCP tool-level error (rather than, say, an uncaught exception crashing the Streamable HTTP
 * session) when the same domain-service call is reached via an {@code @McpTool} instead of a REST
 * controller. Verified manually first (a disposable live instance, hammering {@code
 * create_knowledge_base} past its limit over genuine MCP) before writing this as a permanent
 * regression test, mirroring {@code RateLimitIntegrationTest}'s own approach but through the real
 * MCP Java SDK client instead of a raw {@link java.net.http.HttpClient}.
 */
class McpRateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;

    @MockitoBean private EmbeddingModelFactory embeddingModelFactory;
    @MockitoBean private ChatModelFactory chatModelFactory;

    @LocalServerPort private int port;

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
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(
                        new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));
        when(chatModelFactory.getChatModelForKnowledgeBase(any())).thenReturn(chatModel);
    }

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    /**
     * Fires generously more than the 60/minute capacity at the {@code ask} tool, same over-fire
     * rationale as {@code RateLimitIntegrationTest} (continuous greedy refill means real elapsed
     * wall-clock time during 100 real tool-call round trips genuinely replenishes a few tokens
     * along the way, so only "denial happens somewhere in this batch" is a robust assertion, not an
     * exact call index).
     */
    @Test
    void askIsRateLimitedOverMcpWithACleanToolLevelError() throws Exception {
        String username = "mcp-ratelimit-user-" + UUID.randomUUID();
        registerMcpUser(username, "correct-horse-battery-staple");
        client = buildClient(username, "correct-horse-battery-staple");
        client.initialize();

        callTool("create_tenant", Map.of("name", "MCP Rate Limit Tenant"));
        callTool(
                "configure_model",
                Map.of(
                        "chatProvider", "openai",
                        "chatModel", "gpt-4o",
                        "embeddingProvider", "openai",
                        "embeddingModel", "text-embedding-3-small",
                        "chatApiKey", "sk-fake-chat-key",
                        "embeddingApiKey", "sk-fake-embed-key"));
        asMap(callTool("create_knowledge_base", Map.of("name", "ratelimit-kb")));

        boolean sawSuccess = false;
        boolean sawCleanDenial = false;
        for (int i = 0; i < 100 && !sawCleanDenial; i++) {
            McpSchema.CallToolResult result =
                    client.callTool(
                            new McpSchema.CallToolRequest(
                                    "ask",
                                    Map.of(
                                            "knowledgeBaseName",
                                            "ratelimit-kb",
                                            "question",
                                            "anything?")));
            String text = textOf(result);
            if (Boolean.TRUE.equals(result.isError())) {
                assertThat(text).contains("Rate limit exceeded");
                sawCleanDenial = true;
            } else {
                sawSuccess = true;
            }
        }

        assertThat(sawSuccess)
                .as("at least one call should succeed before the limit trips")
                .isTrue();
        assertThat(sawCleanDenial)
                .as("a denial should surface as a clean MCP tool-level error, not a crash")
                .isTrue();
    }

    private McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
        return client.callTool(new McpSchema.CallToolRequest(name, arguments));
    }

    private String textOf(McpSchema.CallToolResult result) {
        McpSchema.Content content = result.content().get(0);
        assertThat(content).isInstanceOf(McpSchema.TextContent.class);
        return ((McpSchema.TextContent) content).text();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(textOf(result), Map.class);
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
