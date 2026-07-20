package com.synapsemcp.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.AbstractIntegrationTest;
import com.synapsemcp.chat.ChatModelFactory;
import com.synapsemcp.embedding.EmbeddingModelFactory;
import com.synapsemcp.knowledgebase.CreateKnowledgeBaseRequest;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
import com.synapsemcp.tenant.ConfigureModelRequest;
import com.synapsemcp.tenant.CreateTenantRequest;
import com.synapsemcp.tenant.CreateTenantResponse;
import com.synapsemcp.tenant.ModelConfigResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * mcp_plan.md Stage 3: drives {@code ask} past the confirmed 60/minute per-tenant-per-provider
 * limit and confirms a clean {@code 429} + {@code Retry-After} + {@code X-RateLimit-Remaining}
 * response, plus that a different tenant (a separate bucket) is unaffected. No live chat/embedding
 * provider is available in this environment - both factories are {@code @MockitoBean}s, same as
 * every other REST integration test.
 *
 * <p>Uses a plain {@link HttpClient} for the actual rate-limit-triggering calls, not {@link
 * TestRestTemplate} - found live that {@code TestRestTemplate}'s underlying Apache HttpClient5
 * factory automatically retries a {@code 429} response (honoring {@code Retry-After}) until it
 * succeeds, silently absorbing the exact behavior this test needs to observe. {@code
 * TestRestTemplate} is still used for one-shot setup calls (tenant/model-config/KB creation), which
 * aren't rate-limit-sensitive.
 */
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private RedisKeyPrefix redisKeyPrefix;

    @LocalServerPort private int port;

    @MockitoBean private EmbeddingModelFactory embeddingModelFactory;
    @MockitoBean private ChatModelFactory chatModelFactory;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private record TenantFixture(UUID tenantId, String apiKey) {}

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

    private TenantFixture createConfiguredTenant(String name) {
        Set<String> rateLimitKeys =
                redisTemplate.keys(redisKeyPrefix.key("rate_limit:tenant-create:*"));
        if (rateLimitKeys != null && !rateLimitKeys.isEmpty()) {
            redisTemplate.delete(rateLimitKeys);
        }
        CreateTenantResponse tenant =
                restTemplate.postForObject(
                        "/api/v1/tenants",
                        new CreateTenantRequest(name),
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
        return new TenantFixture(tenant.tenantId(), tenant.apiKey());
    }

    private UUID createKnowledgeBase(String apiKey, String name) {
        var response =
                restTemplate.exchange(
                        "/api/v1/knowledgebase",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new CreateKnowledgeBaseRequest(name), bearerHeaders(apiKey)),
                        KnowledgeBaseResponse.class);
        return response.getBody().id();
    }

    private org.springframework.http.HttpHeaders bearerHeaders(String apiKey) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(apiKey);
        return headers;
    }

    private HttpResponse<String> ask(String apiKey, UUID knowledgeBaseId)
            throws IOException, InterruptedException {
        String body =
                "{\"question\":\"anything?\",\"language\":null,\"history\":null,\"mode\":null,"
                        + "\"rerank\":null}";
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        "http://localhost:"
                                                + port
                                                + "/api/v1/knowledgebase/"
                                                + knowledgeBaseId
                                                + "/ask"))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Fires generously more than the 60/minute capacity (each {@code ask} call actually draws from
     * both the {@code CHAT} bucket, via its own {@code @RateLimited}, and the {@code EMBEDDING}
     * bucket, via the internal query-embedding step - {@code createKnowledgeBase} above already
     * spent one {@code EMBEDDING} token too, so {@code EMBEDDING} is the binding constraint here).
     * Doesn't assert denial at one precise call index - {@code Bandwidth.simple}'s continuous
     * greedy refill means real wall-clock elapsed time during this test's own HTTP round trips
     * genuinely replenishes a few tokens along the way, so only "denial happens somewhere in a
     * comfortably over-capacity batch" is a robust assertion, not an exact count.
     */
    @Test
    void askIsRateLimitedOnceTheTenantsBudgetIsExhausted() throws Exception {
        TenantFixture tenant = createConfiguredTenant("Rate limit happy path tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "rate-limit-kb");

        List<HttpResponse<String>> responses = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            responses.add(ask(tenant.apiKey(), kbId));
        }

        assertThat(responses).anyMatch(r -> r.statusCode() == 200);
        HttpResponse<String> denied =
                responses.stream().filter(r -> r.statusCode() == 429).findFirst().orElseThrow();
        assertThat(denied.headers().firstValue("Retry-After")).isPresent();
        assertThat(
                        responses.stream()
                                .filter(r -> r.statusCode() == 200)
                                .findFirst()
                                .orElseThrow()
                                .headers()
                                .firstValue("X-RateLimit-Remaining"))
                .isPresent();
    }

    @Test
    void aDifferentTenantHasItsOwnSeparateBucket() throws Exception {
        TenantFixture exhausted = createConfiguredTenant("Rate limit exhausted tenant");
        UUID exhaustedKbId = createKnowledgeBase(exhausted.apiKey(), "exhausted-kb");
        List<HttpResponse<String>> exhaustedResponses = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            exhaustedResponses.add(ask(exhausted.apiKey(), exhaustedKbId));
        }
        assertThat(exhaustedResponses).anyMatch(r -> r.statusCode() == 429);

        TenantFixture other = createConfiguredTenant("Rate limit unaffected tenant");
        UUID otherKbId = createKnowledgeBase(other.apiKey(), "other-kb");

        HttpResponse<String> otherResponse = ask(other.apiKey(), otherKbId);

        assertThat(otherResponse.statusCode()).isEqualTo(200);
    }
}
