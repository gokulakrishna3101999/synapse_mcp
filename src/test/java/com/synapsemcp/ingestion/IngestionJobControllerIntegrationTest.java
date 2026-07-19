package com.synapsemcp.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.AbstractIntegrationTest;
import com.synapsemcp.common.IngestionStatus;
import com.synapsemcp.document.UploadDocumentResponse;
import com.synapsemcp.embedding.EmbeddingModelFactory;
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
 * rag_plan.md Stage 5: {@code GET /api/v1/jobs/{jobId}} polling endpoint, exercised end-to-end
 * through a real upload so the job genuinely reaches {@code READY} (same {@link EmbeddingModel}
 * mocking approach as {@code DocumentControllerIntegrationTest} - no live embedding provider is
 * available in this environment).
 */
class IngestionJobControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private com.synapsemcp.common.RedisKeyPrefix redisKeyPrefix;
    @Autowired private IngestionJobRepository ingestionJobRepository;

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

    private ResponseEntity<UploadDocumentResponse> uploadDocument(
            String apiKey, UUID knowledgeBaseId, String filename, byte[] content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add(
                "file",
                new ByteArrayResource(content) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                });
        HttpHeaders headers = bearerHeaders(apiKey);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange(
                "/api/v1/knowledgebase/{id}/documents",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                UploadDocumentResponse.class,
                knowledgeBaseId);
    }

    private IngestionStatus awaitTerminalState(UUID jobId) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            IngestionStatus status =
                    ingestionJobRepository.findById(jobId).orElseThrow().getStatus();
            if (status == IngestionStatus.READY || status == IngestionStatus.FAILED) {
                return status;
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

    @Test
    void returnsTheJobStatusOnceThePipelineReachesReady() {
        TenantFixture tenant = createConfiguredTenant("Polling happy path tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "polling-kb");
        ResponseEntity<UploadDocumentResponse> upload =
                uploadDocument(
                        tenant.apiKey(),
                        kbId,
                        "notes.txt",
                        "hello world".getBytes(StandardCharsets.UTF_8));
        UUID jobId = upload.getBody().jobId();
        assertThat(awaitTerminalState(jobId)).isEqualTo(IngestionStatus.READY);

        ResponseEntity<JobStatusResponse> response =
                restTemplate.exchange(
                        "/api/v1/jobs/{jobId}",
                        HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders(tenant.apiKey())),
                        JobStatusResponse.class,
                        jobId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JobStatusResponse body = response.getBody();
        assertThat(body.jobId()).isEqualTo(jobId);
        assertThat(body.documentId()).isEqualTo(upload.getBody().documentId());
        assertThat(body.status()).isEqualTo(IngestionStatus.READY);
        assertThat(body.stage()).isNull();
        assertThat(body.errorDetail()).isNull();
        assertThat(body.createdAt()).isNotNull();
        assertThat(body.updatedAt()).isNotNull();
    }

    @Test
    void returns404ForAJobOwnedByAnotherTenant() {
        TenantFixture owner = createConfiguredTenant("Job owner tenant");
        TenantFixture intruder = createConfiguredTenant("Job intruder tenant");
        UUID kbId = createKnowledgeBase(owner.apiKey(), "polling-isolation-kb");
        ResponseEntity<UploadDocumentResponse> upload =
                uploadDocument(
                        owner.apiKey(),
                        kbId,
                        "notes.txt",
                        "hello world".getBytes(StandardCharsets.UTF_8));
        UUID jobId = upload.getBody().jobId();
        awaitTerminalState(jobId);

        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/api/v1/jobs/{jobId}",
                        HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders(intruder.apiKey())),
                        String.class,
                        jobId);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void returns404ForANonexistentJob() {
        TenantFixture tenant = createConfiguredTenant("Nonexistent job tenant");

        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/api/v1/jobs/{jobId}",
                        HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders(tenant.apiKey())),
                        String.class,
                        UUID.randomUUID());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    /**
     * The app-wide {@code MethodArgumentTypeMismatchException} → {@code 400} mapping (Grooming #36)
     * was verified against other endpoints in earlier stages, but never specifically against this
     * one, added in Stage 5 - confirms a malformed (non-UUID) {@code jobId} path segment doesn't
     * leak a raw {@code 500} from Spring MVC's own argument-resolution failure before {@code
     * IngestionJobController} ever runs.
     */
    @Test
    void returns400ForAMalformedJobId() {
        TenantFixture tenant = createConfiguredTenant("Malformed job id tenant");

        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/api/v1/jobs/{jobId}",
                        HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders(tenant.apiKey())),
                        String.class,
                        "not-a-real-uuid");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    /**
     * rag_plan.md REST API Summary: {@code GET /api/v1/documents/{documentId}/status} - found
     * missing entirely during a completeness audit (`plan.md` §9 2026-07-18); this whole endpoint
     * had no controller at all until this session, despite being listed as part of the "complete
     * list of REST endpoints" for Phase 1. Mirrors {@code GET /api/v1/jobs/{jobId}}'s own test
     * coverage above, since both back the identical {@link JobStatusResponse} shape.
     */
    @Test
    void documentStatusReturnsTheSameShapeAsJobStatusOnceReady() {
        TenantFixture tenant = createConfiguredTenant("Document status happy path tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "document-status-kb");
        ResponseEntity<UploadDocumentResponse> upload =
                uploadDocument(
                        tenant.apiKey(),
                        kbId,
                        "notes.txt",
                        "hello world".getBytes(StandardCharsets.UTF_8));
        UUID documentId = upload.getBody().documentId();
        assertThat(awaitTerminalState(upload.getBody().jobId())).isEqualTo(IngestionStatus.READY);

        ResponseEntity<JobStatusResponse> response =
                restTemplate.exchange(
                        "/api/v1/documents/{documentId}/status",
                        HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders(tenant.apiKey())),
                        JobStatusResponse.class,
                        documentId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JobStatusResponse body = response.getBody();
        assertThat(body.documentId()).isEqualTo(documentId);
        assertThat(body.jobId()).isEqualTo(upload.getBody().jobId());
        assertThat(body.status()).isEqualTo(IngestionStatus.READY);
    }

    @Test
    void documentStatusReturns404ForADocumentOwnedByAnotherTenant() {
        TenantFixture owner = createConfiguredTenant("Document status owner tenant");
        TenantFixture intruder = createConfiguredTenant("Document status intruder tenant");
        UUID kbId = createKnowledgeBase(owner.apiKey(), "document-status-isolation-kb");
        ResponseEntity<UploadDocumentResponse> upload =
                uploadDocument(
                        owner.apiKey(),
                        kbId,
                        "notes.txt",
                        "hello world".getBytes(StandardCharsets.UTF_8));
        UUID documentId = upload.getBody().documentId();
        awaitTerminalState(upload.getBody().jobId());

        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/api/v1/documents/{documentId}/status",
                        HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders(intruder.apiKey())),
                        String.class,
                        documentId);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void documentStatusReturns404ForANonexistentDocument() {
        TenantFixture tenant = createConfiguredTenant("Nonexistent document status tenant");

        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/api/v1/documents/{documentId}/status",
                        HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders(tenant.apiKey())),
                        String.class,
                        UUID.randomUUID());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void documentStatusReturns400ForAMalformedDocumentId() {
        TenantFixture tenant = createConfiguredTenant("Malformed document id tenant");

        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/api/v1/documents/{documentId}/status",
                        HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders(tenant.apiKey())),
                        String.class,
                        "not-a-real-uuid");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
