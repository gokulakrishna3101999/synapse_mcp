package com.synapsemcp.rag.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.AbstractIntegrationTest;
import com.synapsemcp.chat.ChatModelFactory;
import com.synapsemcp.common.IngestionStatus;
import com.synapsemcp.document.UploadDocumentResponse;
import com.synapsemcp.embedding.EmbeddingModelFactory;
import com.synapsemcp.ingestion.IngestionJobRepository;
import com.synapsemcp.knowledgebase.CreateKnowledgeBaseRequest;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
import com.synapsemcp.tenant.ConfigureModelRequest;
import com.synapsemcp.tenant.CreateTenantRequest;
import com.synapsemcp.tenant.CreateTenantResponse;
import com.synapsemcp.tenant.ModelConfigResponse;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Flux;

/**
 * rag_plan.md Stage 6b: {@code POST /api/v1/knowledgebase/{id}/ask}, exercised end-to-end through a
 * real upload/ingest. No live chat/embedding provider is available in this environment (same
 * recurring blocker as every prior stage) - both {@link EmbeddingModelFactory} and {@link
 * ChatModelFactory} are replaced with {@code @MockitoBean}s.
 */
class AskControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private com.synapsemcp.common.RedisKeyPrefix redisKeyPrefix;
    @Autowired private IngestionJobRepository ingestionJobRepository;

    @MockitoBean private EmbeddingModelFactory embeddingModelFactory;
    @MockitoBean private ChatModelFactory chatModelFactory;

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
    }

    private void stubChatAnswer(String answerText) {
        ChatModel chatModel = mock(ChatModel.class);
        AssistantMessage message = new AssistantMessage(answerText);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(message))));
        when(chatModelFactory.getChatModelForKnowledgeBase(any())).thenReturn(chatModel);
    }

    private ChatModel stubChatStream(String... tokens) {
        ChatModel chatModel = mock(ChatModel.class);
        List<ChatResponse> responses = new ArrayList<>();
        for (String token : tokens) {
            responses.add(new ChatResponse(List.of(new Generation(new AssistantMessage(token)))));
        }
        when(chatModel.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.fromIterable(responses));
        when(chatModelFactory.getChatModelForKnowledgeBase(any())).thenReturn(chatModel);
        return chatModel;
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
        ResponseEntity<KnowledgeBaseResponse> response =
                restTemplate.exchange(
                        "/api/v1/knowledgebase",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new CreateKnowledgeBaseRequest(name), bearerHeaders(apiKey)),
                        KnowledgeBaseResponse.class);
        return response.getBody().id();
    }

    private HttpHeaders bearerHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        return headers;
    }

    private void uploadAndAwaitReady(
            String apiKey, UUID knowledgeBaseId, String filename, String content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add(
                "file",
                new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                });
        HttpHeaders headers = bearerHeaders(apiKey);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<UploadDocumentResponse> upload =
                restTemplate.exchange(
                        "/api/v1/knowledgebase/{id}/documents",
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers),
                        UploadDocumentResponse.class,
                        knowledgeBaseId);
        awaitTerminalState(upload.getBody().jobId());
    }

    private void awaitTerminalState(UUID jobId) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            IngestionStatus status =
                    ingestionJobRepository.findById(jobId).orElseThrow().getStatus();
            if (status == IngestionStatus.READY || status == IngestionStatus.FAILED) {
                assertThat(status).isEqualTo(IngestionStatus.READY);
                return;
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

    private ResponseEntity<AskResponse> ask(
            String apiKey, UUID knowledgeBaseId, AskRequest request) {
        HttpHeaders headers = bearerHeaders(apiKey);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return restTemplate.exchange(
                "/api/v1/knowledgebase/{id}/ask",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                AskResponse.class,
                knowledgeBaseId);
    }

    @Test
    void happyPathReturnsAnAnswerWithCitations() {
        TenantFixture tenant = createConfiguredTenant("Ask happy path tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "ask-kb");
        uploadAndAwaitReady(tenant.apiKey(), kbId, "apple.txt", "I really love apple pie recipes");
        stubChatAnswer("Apples are great for pie [Source 1].");

        ResponseEntity<AskResponse> response =
                ask(
                        tenant.apiKey(),
                        kbId,
                        new AskRequest("what do you know about apples?", null, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AskResponse body = response.getBody();
        assertThat(body.answer()).isEqualTo("Apples are great for pie [Source 1].");
        assertThat(body.citations()).hasSize(1);
        assertThat(body.citations().get(0).filename()).isEqualTo("apple.txt");
        assertThat(body.citations().get(0).sourceNumber()).isEqualTo(1);
    }

    @Test
    void emptyKnowledgeBaseShortCircuitsWithoutCallingTheChatModel() {
        TenantFixture tenant = createConfiguredTenant("Ask empty kb tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "ask-empty-kb");

        ResponseEntity<AskResponse> response =
                ask(tenant.apiKey(), kbId, new AskRequest("anything?", null, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().answer())
                .isEqualTo("I don't have enough information to answer that question.");
        assertThat(response.getBody().citations()).isEmpty();
        verify(chatModelFactory, never()).getChatModelForKnowledgeBase(any());
    }

    @Test
    void aKnowledgeBaseOwnedByAnotherTenantReturns404() {
        TenantFixture owner = createConfiguredTenant("Ask owner tenant");
        TenantFixture intruder = createConfiguredTenant("Ask intruder tenant");
        UUID kbId = createKnowledgeBase(owner.apiKey(), "ask-owner-only-kb");
        uploadAndAwaitReady(owner.apiKey(), kbId, "secret.txt", "top secret content");

        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/api/v1/knowledgebase/{id}/ask",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new AskRequest("what is the secret?", null, null, null, null),
                                bearerHeaders(intruder.apiKey())),
                        String.class,
                        kbId);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void blankQuestionReturns400() {
        TenantFixture tenant = createConfiguredTenant("Ask blank question tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "ask-blank-kb");

        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/api/v1/knowledgebase/{id}/ask",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new AskRequest("", null, null, null, null),
                                bearerHeaders(tenant.apiKey())),
                        String.class,
                        kbId);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void sseEndpointStreamsTokensThenACitationsEvent() {
        TenantFixture tenant = createConfiguredTenant("Ask SSE tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "ask-sse-kb");
        uploadAndAwaitReady(tenant.apiKey(), kbId, "banana.txt", "banana bread is delicious");
        stubChatStream("Bananas ", "are ", "great.");

        HttpHeaders headers = bearerHeaders(tenant.apiKey());
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/api/v1/knowledgebase/{id}/ask",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new AskRequest("tell me about bananas", null, null, null, null),
                                headers),
                        String.class,
                        kbId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString())
                .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        String body = response.getBody();
        assertThat(body).contains("data:Bananas").contains("data:are").contains("data:great.");
        assertThat(body).contains("event:citations");
        assertThat(body).contains("banana.txt");
    }
}
