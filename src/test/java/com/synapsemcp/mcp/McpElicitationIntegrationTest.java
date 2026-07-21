package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * mcp_plan.md Grooming #24's elicitation feature, proven over the real wire for the first time
 * (Grooming #27): {@code ConfigureModelMcpToolTest} covers the elicitation logic against a mocked
 * {@code McpSyncRequestContext}, and {@code McpStage2ToolsIntegrationTest} only ever exercises the
 * <em>fallback</em> path (its client declares no elicitation capability, so {@code elicitEnabled()}
 * is false and blank fields pass straight through) - nothing had proven the full protocol loop end
 * to end: the server pausing mid-tool-call, sending a real {@code elicitation/create} request back
 * over Streamable HTTP, the client's handler answering, and the answered values genuinely landing
 * in the persisted model config.
 */
class McpElicitationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;

    @LocalServerPort private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private McpSyncClient client;

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void configureModelElicitsEveryBlankFieldFromTheClientAndPersistsTheAnswers() {
        String username = "mcp-elicit-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";
        registerMcpUser(username, password);

        List<String> promptsReceived = new ArrayList<>();
        client =
                buildElicitingClient(
                        username,
                        password,
                        request -> {
                            promptsReceived.add(request.message());
                            return new McpSchema.ElicitResult(
                                    McpSchema.ElicitResult.Action.ACCEPT,
                                    Map.of("value", answerFor(request.message())));
                        });
        client.initialize();

        callTool("create_tenant", Map.of("name", "Elicitation Tenant"));

        // Deliberately zero arguments: every one of the six fields must be elicited.
        Map<String, Object> config = callTool("configure_model", Map.of());

        assertThat(promptsReceived)
                .as(
                        "all six fields (four always + two API keys, since the elicited provider"
                                + " is openai) must have been requested from the client")
                .hasSize(6);
        assertThat(config.get("chatProvider")).isEqualTo("openai");
        assertThat(config.get("chatModel")).isEqualTo("gpt-4o");
        assertThat(config.get("embeddingProvider")).isEqualTo("openai");
        assertThat(config.get("embeddingModel")).isEqualTo("text-embedding-3-small");
    }

    /**
     * The other half of the contract: a user who declines is never second-guessed - the blank field
     * stays blank and {@code ModelConfigService}'s own transport-agnostic validation rejects it
     * with the same clean error a plainly-omitted field gets, never a fabricated value and never a
     * raw internal error (the exact information-disclosure bug Grooming #24 fixed).
     */
    @Test
    void aDeclinedElicitationLeavesTheFieldBlankAndFailsWithTheCleanValidationError() {
        String username = "mcp-elicit-decline-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";
        registerMcpUser(username, password);

        client =
                buildElicitingClient(
                        username,
                        password,
                        request ->
                                new McpSchema.ElicitResult(
                                        McpSchema.ElicitResult.Action.DECLINE, null));
        client.initialize();

        callTool("create_tenant", Map.of("name", "Elicitation Decline Tenant"));

        McpSchema.CallToolResult result =
                client.callTool(new McpSchema.CallToolRequest("configure_model", Map.of()));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(((McpSchema.TextContent) result.content().get(0)).text())
                .contains("chatProvider must not be blank");
    }

    /**
     * The API-key checks must run first: those prompts ("Please enter your API key for the openai
     * chat provider.") also contain the substring "chat provider", so provider-name matching alone
     * would misroute them.
     */
    private static String answerFor(String prompt) {
        if (prompt.contains("API key")) {
            return prompt.contains("chat") ? "sk-elicited-chat-key" : "sk-elicited-embed-key";
        }
        if (prompt.contains("chat provider")) {
            return "openai";
        }
        if (prompt.contains("chat model")) {
            return "gpt-4o";
        }
        if (prompt.contains("embedding provider")) {
            return "openai";
        }
        return "text-embedding-3-small";
    }

    private void registerMcpUser(String username, String password) {
        ResponseEntity<RegisterMcpUserResponse> response =
                restTemplate.postForEntity(
                        "/api/v1/mcp-users/register",
                        new RegisterMcpUserRequest(username, password, null),
                        RegisterMcpUserResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
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

    private McpSyncClient buildElicitingClient(
            String username,
            String password,
            Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> elicitationHandler) {
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
        return McpClient.sync(transport)
                .capabilities(McpSchema.ClientCapabilities.builder().elicitation().build())
                .elicitation(elicitationHandler)
                .build();
    }
}
