package com.synapsemcp.rag.evaluate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.AbstractIntegrationTest;
import com.synapsemcp.common.IngestionStatus;
import com.synapsemcp.document.UploadDocumentResponse;
import com.synapsemcp.embedding.EmbeddingModelFactory;
import com.synapsemcp.ingestion.IngestionJobRepository;
import com.synapsemcp.knowledgebase.CreateKnowledgeBaseRequest;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
import com.synapsemcp.rag.chunk.ChunkRepository;
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

/**
 * rag_plan.md Stage 6c: {@code POST /api/v1/knowledgebase/{id}/evaluate}, exercised end-to-end
 * through a real upload/ingest so real chunk ids exist to build golden queries against. No live
 * embedding provider is available in this environment (recurring blocker) - {@link
 * EmbeddingModelFactory} is replaced with a {@code @MockitoBean}.
 */
class EvaluationControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private com.synapsemcp.common.RedisKeyPrefix redisKeyPrefix;
    @Autowired private IngestionJobRepository ingestionJobRepository;
    @Autowired private ChunkRepository chunkRepository;

    @MockitoBean private EmbeddingModelFactory embeddingModelFactory;

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
                            for (int i = 0; i < texts.size(); i++) {
                                vectors.add(new float[1536]);
                            }
                            return vectors;
                        });
        when(embeddingModelFactory.getEmbeddingModel(any())).thenReturn(embeddingModel);
        when(embeddingModelFactory.getEmbeddingModelForKnowledgeBase(any()))
                .thenReturn(embeddingModel);
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
        awaitTerminalState(upload.getBody().jobId());
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

    private ResponseEntity<EvaluateResponse> evaluate(
            String apiKey, UUID knowledgeBaseId, EvaluateRequest request) {
        return restTemplate.exchange(
                "/api/v1/knowledgebase/{id}/evaluate",
                HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(apiKey)),
                EvaluateResponse.class,
                knowledgeBaseId);
    }

    @Test
    void perfectGoldenQueryScoresMaximalMetrics() {
        TenantFixture tenant = createConfiguredTenant("Evaluate tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "evaluate-kb");
        UUID documentId =
                uploadAndAwaitReady(tenant.apiKey(), kbId, "apple.txt", "apple pie recipe");
        UUID chunkId = chunkRepository.findByDocument_Id(documentId).get(0).getId();

        ResponseEntity<EvaluateResponse> response =
                evaluate(
                        tenant.apiKey(),
                        kbId,
                        new EvaluateRequest(
                                List.of(new EvaluateQuery("apple", List.of(chunkId))),
                                null,
                                null,
                                null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        EvaluateResponse body = response.getBody();
        assertThat(body.perQuery()).hasSize(1);
        assertThat(body.perQuery().get(0).reciprocalRank()).isCloseTo(1.0, within(1e-9));
        assertThat(body.meanReciprocalRank()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void aQueryWithNoMatchingResultsScoresZero() {
        TenantFixture tenant = createConfiguredTenant("Evaluate zero-score tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "evaluate-zero-kb");
        uploadAndAwaitReady(tenant.apiKey(), kbId, "apple.txt", "apple pie recipe");

        ResponseEntity<EvaluateResponse> response =
                evaluate(
                        tenant.apiKey(),
                        kbId,
                        new EvaluateRequest(
                                List.of(new EvaluateQuery("apple", List.of(UUID.randomUUID()))),
                                null,
                                null,
                                null));

        assertThat(response.getBody().perQuery().get(0).reciprocalRank()).isZero();
    }

    @Test
    void aKnowledgeBaseOwnedByAnotherTenantReturns404() {
        TenantFixture owner = createConfiguredTenant("Evaluate owner tenant");
        TenantFixture intruder = createConfiguredTenant("Evaluate intruder tenant");
        UUID kbId = createKnowledgeBase(owner.apiKey(), "evaluate-owner-only-kb");

        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/api/v1/knowledgebase/{id}/evaluate",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new EvaluateRequest(
                                        List.of(new EvaluateQuery("q", List.of())),
                                        null,
                                        null,
                                        null),
                                bearerHeaders(intruder.apiKey())),
                        String.class,
                        kbId);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void emptyQueriesListReturns400() {
        TenantFixture tenant = createConfiguredTenant("Evaluate empty queries tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "evaluate-empty-kb");

        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/api/v1/knowledgebase/{id}/evaluate",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new EvaluateRequest(List.of(), null, null, null),
                                bearerHeaders(tenant.apiKey())),
                        String.class,
                        kbId);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
