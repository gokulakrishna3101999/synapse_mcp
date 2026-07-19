package com.synapsemcp.rag.retrieve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
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
import java.util.Locale;
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
import org.springframework.core.ParameterizedTypeReference;
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

/**
 * rag_plan.md Stage 6a: {@code POST /api/v1/knowledgebase/{id}/search}, exercised end-to-end
 * through a real upload/ingest so real chunks exist to search over - same {@link EmbeddingModel}
 * mocking approach as every other Stage 5/6 integration test (no live embedding provider is
 * available in this environment). The mocked model returns a small, deterministic keyword-encoded
 * vector (not a real embedding) so vector-mode ranking is genuinely assertable rather than
 * incidental.
 */
class SearchControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private com.synapsemcp.common.RedisKeyPrefix redisKeyPrefix;
    @Autowired private IngestionJobRepository ingestionJobRepository;

    @MockitoBean private EmbeddingModelFactory embeddingModelFactory;
    @MockitoBean private ChatModelFactory chatModelFactory;

    private record TenantFixture(UUID tenantId, String apiKey) {}

    @BeforeEach
    void stubEmbeddingModel() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.dimensions()).thenReturn(1536);
        when(embeddingModel.embed(anyList()))
                .thenAnswer(
                        invocation -> {
                            List<String> texts = invocation.getArgument(0);
                            List<float[]> vectors = new ArrayList<>();
                            for (String text : texts) {
                                vectors.add(keywordEncodedVector(text));
                            }
                            return vectors;
                        });
        when(embeddingModelFactory.getEmbeddingModel(any())).thenReturn(embeddingModel);
        when(embeddingModelFactory.getEmbeddingModelForKnowledgeBase(any()))
                .thenReturn(embeddingModel);
    }

    /**
     * A tiny, deterministic stand-in for a real embedding: one dedicated dimension per keyword this
     * test cares about, set to {@code 1.0} if the text contains that keyword. A real provider is
     * never available in this environment (same recurring blocker every prior stage's tests note),
     * so this is what makes vector-mode ranking assertable instead of arbitrary - two chunks about
     * different keywords land orthogonal to each other, and a query matches whichever chunk shares
     * its keyword. A small constant in an unused slot avoids an all-zero vector for text matching
     * no keyword (pgvector's cosine operator returns {@code NaN} for a zero-norm vector, confirmed
     * live via psql - not a real embedding provider would ever produce one, but this test double
     * could).
     */
    private static float[] keywordEncodedVector(String text) {
        float[] vector = new float[1536];
        String lower = text.toLowerCase(Locale.ROOT);
        vector[0] = lower.contains("apple") ? 1f : 0f;
        vector[1] = lower.contains("banana") ? 1f : 0f;
        vector[2] = 0.001f;
        return vector;
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

    private UUID uploadAndAwaitReady(
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
        UUID jobId = upload.getBody().jobId();
        awaitTerminalState(jobId);
        return upload.getBody().documentId();
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

    private ResponseEntity<List<SearchResultChunk>> search(
            String apiKey, UUID knowledgeBaseId, SearchRequest request) {
        return restTemplate.exchange(
                "/api/v1/knowledgebase/{id}/search",
                HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(apiKey)),
                new ParameterizedTypeReference<>() {},
                knowledgeBaseId);
    }

    @Test
    void vectorModeRanksTheKeywordMatchingChunkFirst() {
        TenantFixture tenant = createConfiguredTenant("Vector search tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "vector-kb");
        uploadAndAwaitReady(tenant.apiKey(), kbId, "apple.txt", "I really love apple pie");
        uploadAndAwaitReady(tenant.apiKey(), kbId, "banana.txt", "banana bread is my favorite");

        ResponseEntity<List<SearchResultChunk>> response =
                search(
                        tenant.apiKey(),
                        kbId,
                        new SearchRequest("apple", null, null, SearchMode.VECTOR, false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<SearchResultChunk> results = response.getBody();
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).filename()).isEqualTo("apple.txt");
        assertThat(results.get(0).content()).contains("apple");
    }

    @Test
    void keywordModeRanksTheLiteralTermMatchFirst() {
        TenantFixture tenant = createConfiguredTenant("Keyword search tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "keyword-kb");
        uploadAndAwaitReady(tenant.apiKey(), kbId, "apple.txt", "I really love apple pie");
        uploadAndAwaitReady(tenant.apiKey(), kbId, "banana.txt", "banana bread is my favorite");

        ResponseEntity<List<SearchResultChunk>> response =
                search(
                        tenant.apiKey(),
                        kbId,
                        new SearchRequest("banana", null, null, SearchMode.KEYWORD, false));

        assertThat(response.getBody()).isNotEmpty();
        assertThat(response.getBody().get(0).filename()).isEqualTo("banana.txt");
    }

    @Test
    void hybridModeIsTheDefaultAndStillRanksTheMatchingChunkFirst() {
        TenantFixture tenant = createConfiguredTenant("Hybrid search tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "hybrid-kb");
        uploadAndAwaitReady(tenant.apiKey(), kbId, "apple.txt", "I really love apple pie");
        uploadAndAwaitReady(tenant.apiKey(), kbId, "banana.txt", "banana bread is my favorite");

        ResponseEntity<List<SearchResultChunk>> response =
                search(tenant.apiKey(), kbId, new SearchRequest("apple", null, null, null, false));

        assertThat(response.getBody()).isNotEmpty();
        assertThat(response.getBody().get(0).filename()).isEqualTo("apple.txt");
    }

    /**
     * rag_plan.md Stage 6a+: end-to-end proof that {@code rerank=true} genuinely re-scores and
     * re-sorts the retrieval-ranked results via the tenant/KB's own configured chat model - not
     * just that {@link com.synapsemcp.rag.retrieve.LlmRerankerService} works in isolation (already
     * covered by its own unit tests). Uses a mocked {@link ChatModel} (no live chat provider is
     * available in this environment, same recurring blocker as every prior stage) that deliberately
     * flips the vector-mode retrieval order, so a passing assertion can only mean the reranker's
     * output - not the original ranking - won.
     */
    @Test
    void rerankTrueReordersResultsUsingTheConfiguredChatModel() {
        TenantFixture tenant = createConfiguredTenant("Rerank tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "rerank-kb");
        uploadAndAwaitReady(tenant.apiKey(), kbId, "apple.txt", "I really love apple pie");
        uploadAndAwaitReady(tenant.apiKey(), kbId, "banana.txt", "banana bread is my favorite");
        // Vector mode alone ranks apple.txt first (keywordEncodedVector matches "apple" exactly).
        // The mocked chat model scores passage 2 (banana.txt) higher, so a rerank=true result with
        // banana.txt first can only be explained by the reranker actually running and winning.
        ChatModel chatModel = mock(ChatModel.class);
        AssistantMessage flippedScores = new AssistantMessage("1: 10\n2: 95");
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(flippedScores))));
        when(chatModelFactory.getChatModelForKnowledgeBase(any())).thenReturn(chatModel);
        when(chatModelFactory.optionsForKnowledgeBase(any()))
                .thenReturn(ChatOptions.builder().model("gpt-4o").build());

        ResponseEntity<List<SearchResultChunk>> response =
                search(
                        tenant.apiKey(),
                        kbId,
                        new SearchRequest("apple", null, null, SearchMode.VECTOR, true));

        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody().get(0).filename()).isEqualTo("banana.txt");
        assertThat(response.getBody().get(0).score()).isEqualTo(95.0);
    }

    @Test
    void topKTruncatesTheMergedResultList() {
        TenantFixture tenant = createConfiguredTenant("TopK tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "topk-kb");
        uploadAndAwaitReady(tenant.apiKey(), kbId, "apple.txt", "I really love apple pie");
        uploadAndAwaitReady(tenant.apiKey(), kbId, "banana.txt", "banana bread is my favorite");

        ResponseEntity<List<SearchResultChunk>> response =
                search(
                        tenant.apiKey(),
                        kbId,
                        new SearchRequest("apple banana", 1, null, null, false));

        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void returnsEmptyListForAKnowledgeBaseWithNoIngestedDocuments() {
        TenantFixture tenant = createConfiguredTenant("Empty kb tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "empty-kb");

        ResponseEntity<List<SearchResultChunk>> response =
                search(
                        tenant.apiKey(),
                        kbId,
                        new SearchRequest("anything", null, null, null, false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void aKnowledgeBaseOwnedByAnotherTenantReturns404() {
        TenantFixture owner = createConfiguredTenant("Search owner tenant");
        TenantFixture intruder = createConfiguredTenant("Search intruder tenant");
        UUID kbId = createKnowledgeBase(owner.apiKey(), "owner-only-kb");
        uploadAndAwaitReady(owner.apiKey(), kbId, "secret.txt", "top secret apple recipe");

        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/api/v1/knowledgebase/{id}/search",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new SearchRequest("apple", null, null, null, false),
                                bearerHeaders(intruder.apiKey())),
                        String.class,
                        kbId);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void searchNeverCrossesKnowledgeBasesWithinTheSameTenant() {
        TenantFixture tenant = createConfiguredTenant("Cross kb tenant");
        UUID kbOne = createKnowledgeBase(tenant.apiKey(), "kb-one");
        UUID kbTwo = createKnowledgeBase(tenant.apiKey(), "kb-two");
        uploadAndAwaitReady(tenant.apiKey(), kbOne, "one.txt", "apple content in kb one");
        uploadAndAwaitReady(tenant.apiKey(), kbTwo, "two.txt", "apple content in kb two");

        ResponseEntity<List<SearchResultChunk>> response =
                search(tenant.apiKey(), kbOne, new SearchRequest("apple", null, null, null, false));

        assertThat(response.getBody()).allMatch(result -> result.filename().equals("one.txt"));
    }

    @Test
    void blankQueryReturns400() {
        TenantFixture tenant = createConfiguredTenant("Blank query tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "blank-query-kb");

        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/api/v1/knowledgebase/{id}/search",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new SearchRequest("", null, null, null, false),
                                bearerHeaders(tenant.apiKey())),
                        String.class,
                        kbId);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void invalidModeValueReturns400() {
        TenantFixture tenant = createConfiguredTenant("Invalid mode tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "invalid-mode-kb");
        HttpHeaders headers = bearerHeaders(tenant.apiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/api/v1/knowledgebase/{id}/search",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                "{\"query\":\"apple\",\"mode\":\"not-a-real-mode\"}", headers),
                        String.class,
                        kbId);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
