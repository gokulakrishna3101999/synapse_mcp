package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.AbstractIntegrationTest;
import com.synapsemcp.tenant.ApiKeyRepository;
import com.synapsemcp.tenant.TenantRepository;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * mcp_plan.md Stage 1's own explicit requirement: "Integration test: scripted MCP client logging in
 * via Basic Auth, calling create_tenant". Uses the real MCP Java SDK client ({@code
 * io.modelcontextprotocol.client}) - already a transitive dependency of the server starter, no new
 * test-scope dependency needed - connecting over genuine Streamable HTTP to this test's own real
 * embedded Tomcat instance, not a mocked transport.
 */
class McpAuthFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private McpUserRepository mcpUserRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private TenantRepository tenantRepository;

    @LocalServerPort private int port;

    private McpSyncClient client;

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void registerConnectAndCreateTenantEndToEnd() {
        String username = "mcp-flow-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";
        registerMcpUser(username, password);

        client = buildClient(username, password);
        client.initialize();

        List<String> toolNames =
                client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
        assertThat(toolNames).contains("create_tenant");

        McpSchema.CallToolResult firstCall =
                client.callTool(
                        new McpSchema.CallToolRequest(
                                "create_tenant", Map.of("name", "Flow Tenant")));
        assertThat(firstCall.isError()).isNotEqualTo(Boolean.TRUE);

        McpUser user = mcpUserRepository.findByUsername(username).orElseThrow();
        UUID tenantId = user.getTenantId();
        assertThat(tenantId).isNotNull();
        assertThat(tenantRepository.findById(tenantId)).isPresent();
        assertThat(
                        apiKeyRepository.findAll().stream()
                                .anyMatch(k -> k.getTenantId().equals(tenantId)))
                .isTrue();

        McpSchema.CallToolResult secondCall =
                client.callTool(
                        new McpSchema.CallToolRequest(
                                "create_tenant", Map.of("name", "Second Attempt Tenant")));
        assertThat(secondCall.isError()).isEqualTo(Boolean.TRUE);

        McpUser userAfterSecondCall = mcpUserRepository.findByUsername(username).orElseThrow();
        assertThat(userAfterSecondCall.getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void wrongPasswordIsRejected() {
        String username = "mcp-flow-user-" + UUID.randomUUID();
        registerMcpUser(username, "correct-password");

        client = buildClient(username, "wrong-password");

        org.assertj.core.api.Assertions.assertThatThrownBy(client::initialize);
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
