package com.synapsemcp.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.AbstractIntegrationTest;
import com.synapsemcp.common.IngestionStatus;
import com.synapsemcp.embedding.EmbeddingModelFactory;
import com.synapsemcp.ingestion.IngestionJob;
import com.synapsemcp.ingestion.IngestionJobRepository;
import com.synapsemcp.knowledgebase.CreateKnowledgeBaseRequest;
import com.synapsemcp.knowledgebase.KnowledgeBaseRepository;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
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
 * No live embedding provider is available in this environment (same blocker as every prior stage) -
 * {@link EmbeddingModelFactory} is replaced with a {@code @MockitoBean}, same as {@code
 * KnowledgeBaseIntegrationTest}. {@link com.synapsemcp.ingestion.IngestionPipelineService} is left
 * real (not mocked) - it's the genuine Stage 5 pipeline now, so {@link #stubEmbeddingModel()} stubs
 * both the tenant-scoped lookup (KB creation's {@code embedding_dim} probe, Stage 3) and the
 * knowledge_base-scoped lookup ({@code ChunkEmbeddingService}, Stage 5c) on the same mock model,
 * letting plain-text uploads ingest all the way to {@code READY} for real.
 */
class DocumentControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private com.synapsemcp.common.RedisKeyPrefix redisKeyPrefix;
    @Autowired private KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired private DocumentRepository documentRepository;
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

    /**
     * {@link com.synapsemcp.ingestion.IngestionPipelineService} genuinely runs now (Stage 5) -
     * unlike the old stub, it writes {@code documents}/{@code ingestion_jobs} rows on a background
     * thread the moment {@code 202} returns. Several tests below manually seed a {@code FAILED} row
     * afterward to test the retry path (Grooming #26) - without waiting for the real pipeline to
     * reach a terminal state first, that seeding races the pipeline's own writes, and either can
     * clobber the other. No polling endpoint exists yet (Stage 5's {@code GET /api/v1/jobs/{jobId}}
     * is a separate, not-yet-built task), so this polls the repository directly.
     */
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

    private ResponseEntity<String> uploadDocumentRaw(
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
                String.class,
                knowledgeBaseId);
    }

    @Test
    void uploadsAPlainTextDocumentAndReturns202() {
        TenantFixture tenant = createConfiguredTenant("Upload happy path tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "upload-kb");

        ResponseEntity<UploadDocumentResponse> response =
                uploadDocument(
                        tenant.apiKey(),
                        kbId,
                        "notes.txt",
                        "hello world".getBytes(StandardCharsets.UTF_8));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().status()).isEqualTo(IngestionStatus.PENDING);
        assertThat(response.getBody().dispatched()).isTrue();

        assertThat(awaitTerminalState(response.getBody().jobId())).isEqualTo(IngestionStatus.READY);
    }

    @Test
    void rejectsAnUnsupportedFileTypeWith415() {
        TenantFixture tenant = createConfiguredTenant("Unsupported type tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "upload-kb");

        ResponseEntity<String> response =
                uploadDocumentRaw(
                        tenant.apiKey(),
                        kbId,
                        "payload.bin",
                        new byte[] {0x01, 0x02, 0x03, (byte) 0xFF});

        assertThat(response.getStatusCode().value()).isEqualTo(415);
    }

    @Test
    void rejectsAnEmptyFileWith400() {
        TenantFixture tenant = createConfiguredTenant("Empty file tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "upload-kb");

        ResponseEntity<String> response =
                uploadDocumentRaw(tenant.apiKey(), kbId, "empty.txt", new byte[0]);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void tenantBCannotUploadToTenantAsKnowledgeBase() {
        TenantFixture tenantA = createConfiguredTenant("Owner tenant");
        TenantFixture tenantB = createConfiguredTenant("Intruder tenant");
        UUID kbId = createKnowledgeBase(tenantA.apiKey(), "private-kb");

        ResponseEntity<String> response =
                uploadDocumentRaw(
                        tenantB.apiKey(),
                        kbId,
                        "notes.txt",
                        "hello world".getBytes(StandardCharsets.UTF_8));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void reuploadingIdenticalContentShortCircuitsWith200AndTheSameIds() {
        TenantFixture tenant = createConfiguredTenant("Duplicate content tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "upload-kb");
        byte[] content = "duplicate content check".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<UploadDocumentResponse> first =
                uploadDocument(tenant.apiKey(), kbId, "a.txt", content);
        awaitTerminalState(first.getBody().jobId());
        ResponseEntity<UploadDocumentResponse> second =
                uploadDocument(tenant.apiKey(), kbId, "a.txt", content);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().documentId()).isEqualTo(first.getBody().documentId());
        assertThat(second.getBody().jobId()).isEqualTo(first.getBody().jobId());
        assertThat(second.getBody().dispatched()).isFalse();
    }

    /**
     * Grooming #26: re-uploading identical content against a {@code FAILED} document resets and
     * re-dispatches it, rather than staying permanently stuck. Seeds the {@code FAILED} state
     * directly via the repositories (deterministically, rather than trying to force the real Stage
     * 5 pipeline to fail on demand) - the same fixture-seeding approach {@code
     * KnowledgeBaseIntegrationTest} already uses for states that can't be reached through the API
     * alone. Waits for the first upload's own real pipeline run to finish first (whatever it
     * settles on), so that run's writes can't race the manual seeding below.
     */
    @Test
    void reuploadingIdenticalContentAgainstAFailedJobResetsAndRedispatches() {
        TenantFixture tenant = createConfiguredTenant("Failed retry tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "upload-kb");
        byte[] content = "retry after failure content".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<UploadDocumentResponse> first =
                uploadDocument(tenant.apiKey(), kbId, "a.txt", content);
        UUID documentId = first.getBody().documentId();
        UUID jobId = first.getBody().jobId();
        awaitTerminalState(jobId);

        Document document = documentRepository.findById(documentId).orElseThrow();
        document.setStatus(IngestionStatus.FAILED);
        documentRepository.save(document);
        IngestionJob job = ingestionJobRepository.findById(jobId).orElseThrow();
        job.setStatus(IngestionStatus.FAILED);
        job.setErrorDetail("simulated provider outage");
        job.setStage("EMBED");
        ingestionJobRepository.save(job);

        ResponseEntity<UploadDocumentResponse> retry =
                uploadDocument(tenant.apiKey(), kbId, "a.txt", content);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(retry.getBody().documentId()).isEqualTo(documentId);
        assertThat(retry.getBody().jobId()).isEqualTo(jobId);
        assertThat(retry.getBody().status()).isEqualTo(IngestionStatus.PENDING);
        assertThat(retry.getBody().dispatched()).isTrue();

        Document resetDocument = documentRepository.findById(documentId).orElseThrow();
        IngestionJob resetJob = ingestionJobRepository.findById(jobId).orElseThrow();
        assertThat(resetDocument.getStatus()).isEqualTo(IngestionStatus.PENDING);
        assertThat(resetJob.getStatus()).isEqualTo(IngestionStatus.PENDING);
        assertThat(resetJob.getErrorDetail()).isNull();
        assertThat(resetJob.getStage()).isNull();

        awaitTerminalState(jobId);
    }

    /**
     * Content-sniffed via real Tika detection, not extension-trusted (rag_plan.md Stage 4's own
     * example, applied end-to-end through the real HTTP stack): HTML bytes named {@code .txt} must
     * still be accepted as HTML, not rejected or misclassified.
     */
    @Test
    void detectsContentTypeFromBytesNotFromTheClaimedFilename() {
        TenantFixture tenant = createConfiguredTenant("Content sniffing tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "upload-kb");

        ResponseEntity<UploadDocumentResponse> response =
                uploadDocument(
                        tenant.apiKey(),
                        kbId,
                        "not-really-html.txt",
                        "<!DOCTYPE html><html><body>hi</body></html>"
                                .getBytes(StandardCharsets.UTF_8));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        awaitTerminalState(response.getBody().jobId());
    }

    @Test
    void doesNotLeaveOrphanedRowsWhenValidationFails() {
        TenantFixture tenant = createConfiguredTenant("No orphan rows tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "upload-kb");

        uploadDocumentRaw(
                tenant.apiKey(), kbId, "payload.bin", new byte[] {0x01, 0x02, 0x03, (byte) 0xFF});

        assertThat(
                        documentRepository.findByTenantIdAndKnowledgeBase_IdAndContentHash(
                                tenant.tenantId(),
                                kbId,
                                ContentHasher.sha256Hex(
                                        new byte[] {0x01, 0x02, 0x03, (byte) 0xFF})))
                .isEmpty();
    }

    /**
     * Found live (a later, more exhaustive audit pass, `plan.md` §9 2026-07-17): concurrent uploads
     * of identical new content raced past the un-synchronized idempotency check-then-insert, so N-1
     * of N simultaneous duplicate uploads got a raw {@code 409} with no job info instead of the
     * promised idempotent short-circuit. User confirmed (recommended option) a pessimistic lock on
     * the knowledge_base row. This can only be proven with a real concurrent-thread test against a
     * real database - a unit test with mocks can't exercise actual Postgres row-locking.
     */
    @Test
    void concurrentUploadsOfIdenticalNewContentNeverProduceARaw409() throws Exception {
        TenantFixture tenant = createConfiguredTenant("Concurrent duplicate upload tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "upload-kb");
        byte[] content = "concurrent duplicate content".getBytes(StandardCharsets.UTF_8);
        int concurrency = 10;

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<ResponseEntity<UploadDocumentResponse>>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(
                        executor.submit(
                                () -> uploadDocument(tenant.apiKey(), kbId, "a.txt", content)));
            }

            List<ResponseEntity<UploadDocumentResponse>> responses = new ArrayList<>();
            for (Future<ResponseEntity<UploadDocumentResponse>> future : futures) {
                responses.add(future.get());
            }

            for (ResponseEntity<UploadDocumentResponse> response : responses) {
                assertThat(response.getStatusCode())
                        .as(
                                "every concurrent duplicate upload must resolve gracefully, never a"
                                        + " raw 409")
                        .isIn(HttpStatus.ACCEPTED, HttpStatus.OK);
                assertThat(response.getBody()).isNotNull();
            }

            Set<UUID> distinctDocumentIds =
                    responses.stream()
                            .map(r -> r.getBody().documentId())
                            .collect(Collectors.toSet());
            assertThat(distinctDocumentIds)
                    .as("all concurrent duplicate uploads must resolve to the same document")
                    .hasSize(1);
            long acceptedCount =
                    responses.stream()
                            .filter(r -> r.getStatusCode() == HttpStatus.ACCEPTED)
                            .count();
            assertThat(acceptedCount).as("exactly one winner should get 202").isEqualTo(1);

            awaitTerminalState(responses.get(0).getBody().jobId());
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Same root cause and fix as the test above, for the retry path: concurrent re-uploads of a
     * {@code FAILED} document's identical content must dispatch the pipeline exactly once, not once
     * per racing request.
     */
    @Test
    void concurrentRetriesOfAFailedDocumentDispatchExactlyOnce() throws Exception {
        TenantFixture tenant = createConfiguredTenant("Concurrent failed retry tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "upload-kb");
        byte[] content = "concurrent failed retry content".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<UploadDocumentResponse> first =
                uploadDocument(tenant.apiKey(), kbId, "a.txt", content);
        UUID documentId = first.getBody().documentId();
        UUID jobId = first.getBody().jobId();
        awaitTerminalState(jobId);

        Document document = documentRepository.findById(documentId).orElseThrow();
        document.setStatus(IngestionStatus.FAILED);
        documentRepository.save(document);
        IngestionJob job = ingestionJobRepository.findById(jobId).orElseThrow();
        job.setStatus(IngestionStatus.FAILED);
        job.setErrorDetail("simulated provider outage");
        ingestionJobRepository.save(job);

        int concurrency = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<ResponseEntity<UploadDocumentResponse>>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(
                        executor.submit(
                                () -> uploadDocument(tenant.apiKey(), kbId, "a.txt", content)));
            }

            long dispatchedCount = 0;
            for (Future<ResponseEntity<UploadDocumentResponse>> future : futures) {
                ResponseEntity<UploadDocumentResponse> response = future.get();
                assertThat(response.getBody().documentId()).isEqualTo(documentId);
                assertThat(response.getBody().jobId()).isEqualTo(jobId);
                if (response.getBody().dispatched()) {
                    dispatchedCount++;
                }
            }

            assertThat(dispatchedCount)
                    .as("exactly one of the racing retries should actually trigger a dispatch")
                    .isEqualTo(1);

            awaitTerminalState(jobId);
        } finally {
            executor.shutdown();
        }
    }
}
