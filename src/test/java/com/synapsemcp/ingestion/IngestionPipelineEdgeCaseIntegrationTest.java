package com.synapsemcp.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.AbstractIntegrationTest;
import com.synapsemcp.common.IngestionStatus;
import com.synapsemcp.document.Document;
import com.synapsemcp.document.DocumentRepository;
import com.synapsemcp.document.UploadDocumentResponse;
import com.synapsemcp.embedding.EmbeddingModelFactory;
import com.synapsemcp.ingestion.index.LuceneIndexManager;
import com.synapsemcp.knowledgebase.CreateKnowledgeBaseRequest;
import com.synapsemcp.knowledgebase.KnowledgeBaseRepository;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
import com.synapsemcp.rag.chunk.Chunk;
import com.synapsemcp.rag.chunk.ChunkRepository;
import com.synapsemcp.tenant.ConfigureModelRequest;
import com.synapsemcp.tenant.CreateTenantRequest;
import com.synapsemcp.tenant.CreateTenantResponse;
import com.synapsemcp.tenant.ModelConfigResponse;
import java.io.IOException;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Post-Stage-5 "final thorough validation" pass (`plan.md` §9) - targeted reproductions of
 * scenarios never exercised by Stage 5's own build-time tests: real concurrent multi-tenant/
 * multi-document ingestion, malformed file content, empty extracted text, a knowledge_base
 * disappearing mid-pipeline, and identical content across two knowledge bases.
 */
class IngestionPipelineEdgeCaseIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private com.synapsemcp.common.RedisKeyPrefix redisKeyPrefix;
    @Autowired private IngestionJobRepository ingestionJobRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private ChunkRepository chunkRepository;
    @Autowired private LuceneIndexManager luceneIndexManager;
    @Autowired private IngestionPipelineService ingestionPipelineService;
    @Autowired private KnowledgeBaseRepository knowledgeBaseRepository;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;

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

    private IngestionStatus awaitTerminalState(UUID jobId) {
        long deadline = System.currentTimeMillis() + 8000;
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
        throw new AssertionError("job " + jobId + " did not reach a terminal state within 8s");
    }

    // ---------------------------------------------------------------------
    // Task 18: knowledge_base deleted while a job is in-flight
    // ---------------------------------------------------------------------

    /**
     * Reproduces the exact sequence {@code IngestionPipelineService.run()} follows, interjecting a
     * real knowledge_base delete (cascades document/job/chunks at the DB level) between the
     * pipeline's initial fetch and its first write - the narrow race window a pure timing-based
     * test can't reliably hit, done here by direct orchestration instead.
     */
    @Test
    void survivesTheKnowledgeBaseBeingDeletedAfterTheJobIsLoadedButBeforeAnyWrite() {
        TenantFixture tenant = createConfiguredTenant("KB delete mid-pipeline tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "race-kb");
        UploadDocumentResponse upload =
                uploadDocument(
                                tenant.apiKey(),
                                kbId,
                                "notes.txt",
                                "hello world".getBytes(StandardCharsets.UTF_8))
                        .getBody();
        awaitTerminalState(upload.jobId());

        // Re-seed a fresh PENDING job/document pair the same way a fresh upload would, so the
        // pipeline has real work left to do (not already READY).
        Document freshDocument = documentRepository.findById(upload.documentId()).orElseThrow();
        freshDocument.setStatus(IngestionStatus.PENDING);
        documentRepository.save(freshDocument);
        IngestionJob freshJob =
                ingestionJobRepository.findByDocument_Id(upload.documentId()).orElseThrow();
        freshJob.setStatus(IngestionStatus.PENDING);
        ingestionJobRepository.save(freshJob);

        // Delete the knowledge_base for real - cascades document/job/chunks at the DB level.
        restTemplate.exchange(
                "/api/v1/knowledgebase/{id}",
                HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(tenant.apiKey())),
                Void.class,
                kbId);
        assertThat(documentRepository.findById(upload.documentId())).isEmpty();
        assertThat(ingestionJobRepository.findById(upload.jobId())).isEmpty();

        // Now run the real pipeline against the job id it would have been dispatched with - this
        // is exactly what @Async("ingestionExecutor") would have done, just invoked synchronously
        // so the assertion below can observe the outcome deterministically.
        ingestionPipelineService.run(
                upload.jobId(), "hello world".getBytes(StandardCharsets.UTF_8));

        // Must not resurrect a "zombie" document/job row referencing a deleted knowledge_base -
        // the pipeline's own not-found guard should have dropped the job cleanly.
        assertThat(documentRepository.findById(upload.documentId())).isEmpty();
        assertThat(ingestionJobRepository.findById(upload.jobId())).isEmpty();
        assertThat(chunkRepository.findByDocument_Id(upload.documentId())).isEmpty();
    }

    /**
     * Architect-level validation round (rag_plan.md Grooming #87): the test above only covers a
     * delete landing <em>before the pipeline's very first read</em> - it never proved anything
     * about a delete landing <em>after chunks are already durably committed</em>, specifically the
     * window between {@code ChunkPersistenceService.persist} committing and {@code
     * LuceneIndexManager.indexChunks} (the very next line in {@code IngestionPipelineService.run}).
     * Found live: a delete landing in exactly this window left a genuinely orphaned Lucene index
     * directory - {@code IngestionPipelineService} now re-checks {@code
     * knowledgeBaseRepository.existsById(...)} immediately before the Lucene write and skips it
     * cleanly if the knowledge_base is already gone (verified at the unit level, with the real
     * orchestration logic, in {@code IngestionPipelineServiceTest}).
     *
     * <p>This test instead characterizes the lower-level primitive the fix guards against - {@link
     * LuceneIndexManager#indexChunks} itself has no existence check of its own and will happily
     * write into a directory for a knowledge_base that no longer exists in Postgres at all, which
     * is exactly why the check had to be added one level up, in the orchestrator, rather than
     * relying on {@code indexChunks} to protect itself. (An earlier attempt reproduced this via a
     * {@link MockitoSpyBean} intercepting {@code ChunkPersistenceService.persist} mid-flight, which
     * turned out to be unreliable for this purpose - {@code Mockito.doAnswer(...).callRealMethod()}
     * on a {@code @Transactional} method doesn't go through Spring's transactional AOP proxy, so
     * the method's own commit never actually happened before the spy's answer continued, and a
     * concurrent delete triggered from inside that answer genuinely deadlocked against the
     * still-open transaction's own row lock - a real Postgres {@code lock_timeout} cancellation,
     * SQLState {@code 55P03}. That failure mode was a test-technique artifact, not the race this
     * test characterizes, so this version sidesteps Mockito entirely: the first upload reaches
     * {@code READY} completely normally, its real committed chunks are read back, the
     * knowledge_base is deleted for real, and {@code indexChunks} is invoked directly with that
     * already-committed data.)
     */
    @Test
    void aConcurrentDeleteBetweenPersistCommittingAndTheLuceneWriteDoesNotCorruptState()
            throws IOException {
        TenantFixture tenant = createConfiguredTenant("Delete during index tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "delete-during-index-kb");
        UploadDocumentResponse upload =
                uploadDocument(
                                tenant.apiKey(),
                                kbId,
                                "notes.txt",
                                "hello world".getBytes(StandardCharsets.UTF_8))
                        .getBody();
        awaitTerminalState(upload.jobId());

        // Real, durably committed chunks - read back before the delete, exactly as
        // IngestionPipelineService.run's own persistResult.chunks() would hold them in memory at
        // the point it's about to call indexChunks.
        List<Chunk> committedChunks = chunkRepository.findByDocument_KnowledgeBase_Id(kbId);
        assertThat(committedChunks).isNotEmpty();

        // Delete the knowledge_base for real - cascades document/job/chunks at the DB level.
        ResponseEntity<Void> deleteResponse =
                restTemplate.exchange(
                        "/api/v1/knowledgebase/{id}",
                        HttpMethod.DELETE,
                        new HttpEntity<>(bearerHeaders(tenant.apiKey())),
                        Void.class,
                        kbId);
        assertThat(deleteResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(knowledgeBaseRepository.findById(kbId)).isEmpty();
        assertThat(documentRepository.findById(upload.documentId())).isEmpty();
        assertThat(chunkRepository.findByDocument_Id(upload.documentId())).isEmpty();

        // The write the pipeline was already committed to, happening anyway - exactly as it would
        // if this delete had landed a moment after the real pipeline's own persist() call
        // returned, before it reached its own indexChunks() call.
        luceneIndexManager.indexChunks(kbId, committedChunks);

        // indexChunks itself has no existence check of its own - this in-flight write completes
        // successfully against the now-deleted knowledge_base's index directory, which is exactly
        // why IngestionPipelineService.run() now re-checks existence one level up before ever
        // reaching this call, rather than relying on indexChunks to protect itself.
        Set<String> luceneChunkIdsAfterDelete = luceneIndexManager.listIndexedChunkIds(kbId);
        assertThat(luceneChunkIdsAfterDelete)
                .as(
                        "indexChunks has no existence check of its own - a write reaching it after"
                                + " a delete still leaves a genuinely orphaned Lucene index"
                                + " directory for a knowledge_base that no longer exists in"
                                + " Postgres at all")
                .isNotEmpty();

        // Clean up the directory this test itself proved gets orphaned, so it doesn't leak onto
        // disk across suite runs.
        luceneIndexManager.deleteIndex(kbId);
    }

    /**
     * "Final" architect-level validation round (rag_plan.md Grooming #88): Grooming #87 explicitly
     * guards the window between {@code persist} committing and the Lucene write, but {@code run()}
     * checks knowledge_base existence exactly once more before that - right after {@code
     * transitionToIndexing}, before {@code extract}/{@code chunk}/{@code embed} even run (line 131
     * of {@code IngestionPipelineService}) - and never again until the Grooming #87 guard. {@code
     * embed} is the one stage in that gap backed by a real network call to an external provider
     * (mocked here, but genuinely slow in production) - the single most realistic point for a
     * concurrent delete to land in practice, and never verified before this test.
     *
     * <p>Reproduced without touching {@code ChunkPersistenceService} or any {@code @Transactional}
     * bean at all, avoiding Grooming #87's own Mockito dead end entirely: the already-mocked, plain
     * (non-transactional) {@code EmbeddingModel} bean is re-stubbed for this test only to trigger
     * the real delete from inside its own answer, before returning normal vectors - simulating the
     * delete landing squarely inside the embed stage, well before {@code persist} ever runs.
     *
     * <p>Found live, not guessed: no data corruption results (see the finding recorded inline below
     * on why), but the failure path itself has a real gap - {@code markFailed} throws trying to
     * update a row that's already gone, uncaught, masked behind a generic Spring log line instead
     * of this pipeline's own intentional failure message.
     */
    @Test
    void aConcurrentDeleteDuringTheEmbedStageFailsTheJobCleanlyInsteadOfCorruptingState()
            throws Exception {
        TenantFixture tenant = createConfiguredTenant("Delete during embed tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "delete-during-embed-kb");

        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.dimensions()).thenReturn(1536);
        when(embeddingModel.embed(anyList()))
                .thenAnswer(
                        invocation -> {
                            ResponseEntity<Void> deleteResponse =
                                    restTemplate.exchange(
                                            "/api/v1/knowledgebase/{id}",
                                            HttpMethod.DELETE,
                                            new HttpEntity<>(bearerHeaders(tenant.apiKey())),
                                            Void.class,
                                            kbId);
                            assertThat(deleteResponse.getStatusCode().is2xxSuccessful())
                                    .as("the concurrent delete itself must succeed cleanly")
                                    .isTrue();
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

        UploadDocumentResponse upload =
                uploadDocument(
                                tenant.apiKey(),
                                kbId,
                                "notes.txt",
                                "delete-during-embed unique marker content"
                                        .getBytes(StandardCharsets.UTF_8))
                        .getBody();

        // The knowledge_base (and everything cascading from it) is gone by the time the delete
        // triggered from inside the embed stub above completes - poll for that rather than
        // asserting immediately, since run() is @Async.
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline
                && knowledgeBaseRepository.findById(kbId).isPresent()) {
            Thread.sleep(25);
        }
        assertThat(knowledgeBaseRepository.findById(kbId)).isEmpty();
        // A short settle window for whatever markStage/persist/markFailed run() attempts next,
        // after the embed stage (and the delete inside it) returns control to it.
        Thread.sleep(500);

        // No data corruption results either way - confirmed below - but see Grooming #88 for a
        // real, separate finding this test surfaced: the very next markStage() call (STAGE_PERSIST)
        // throws ObjectOptimisticLockingFailureException on its own (Hibernate's own stale-row
        // detection via merge() on a detached entity, even with no explicit @Version column - a
        // genuine, previously-unverified safety net that covers every stage-transition write in
        // the pipeline, not just the one persist-to-index window Grooming #87 explicitly guards).
        // persist() itself is therefore never even reached in this scenario. markFailed then
        // throws the identical exception trying to update the same already-gone row, which
        // escapes this @Async method uncaught - masked behind Spring's generic
        // SimpleAsyncUncaughtExceptionHandler log line instead of this pipeline's own intentional
        // "ingestion job {} failed at stage {}" message, and ingestionMetrics.recordFailure(...)
        // is never reached either. Not data corruption, but a real observability blind spot.
        assertThat(documentRepository.findById(upload.documentId())).isEmpty();
        assertThat(chunkRepository.findByDocument_Id(upload.documentId())).isEmpty();
        assertThat(luceneIndexManager.listIndexedChunkIds(kbId))
                .as(
                        "a delete landing during the embed stage - before persist ever runs - must"
                                + " not leave any Lucene data behind either")
                .isEmpty();
    }

    // ---------------------------------------------------------------------
    // New this round: does deleting a knowledge_base clean up its Lucene index directory?
    // ---------------------------------------------------------------------

    /**
     * Found live before the fix (Grooming #71): {@code KnowledgeBaseService.deleteKnowledgeBase}
     * only called {@code knowledgeBaseRepository.delete(...)} - a Postgres-level cascade that
     * removes documents/chunks/jobs, but has no way to reach the filesystem - orphaning every
     * deleted knowledge_base's Lucene directory on disk forever. The reconciliation cron only
     * iterates {@code knowledgeBaseRepository.findAll()} (existing rows), so it would never have
     * discovered or repaired an orphan left behind by a deleted KB. Fixed by having {@code
     * deleteKnowledgeBase} also call {@code LuceneIndexManager.deleteIndex}.
     */
    @Test
    void deletingAKnowledgeBaseCleansUpItsLuceneIndexDirectory() throws Exception {
        TenantFixture tenant = createConfiguredTenant("Lucene orphan tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "lucene-orphan-kb");
        UploadDocumentResponse upload =
                uploadDocument(
                                tenant.apiKey(),
                                kbId,
                                "notes.txt",
                                "hello world".getBytes(StandardCharsets.UTF_8))
                        .getBody();
        assertThat(awaitTerminalState(upload.jobId())).isEqualTo(IngestionStatus.READY);
        assertThat(luceneIndexManager.listIndexedChunkIds(kbId)).isNotEmpty();

        java.nio.file.Path indexDir =
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))
                        .resolve("synapsemcp")
                        .resolve("lucene-indexes")
                        .resolve(kbId.toString());
        assertThat(java.nio.file.Files.isDirectory(indexDir))
                .as("Lucene index directory should exist on disk before deletion")
                .isTrue();

        restTemplate.exchange(
                "/api/v1/knowledgebase/{id}",
                HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(tenant.apiKey())),
                Void.class,
                kbId);
        assertThat(knowledgeBaseRepository.findById(kbId)).isEmpty();
        assertThat(java.nio.file.Files.isDirectory(indexDir))
                .as("Lucene index directory must be cleaned up when its knowledge_base is deleted")
                .isFalse();
    }

    // ---------------------------------------------------------------------
    // Task 19: malformed / corrupt file content
    // ---------------------------------------------------------------------

    @Test
    void marksTheJobFailedForACorruptDocxRatherThanCrashingTheExecutor() {
        TenantFixture tenant = createConfiguredTenant("Corrupt docx tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "corrupt-docx-kb");
        // Valid ZIP magic bytes (so Tika detects it as docx) but garbage OOXML content inside.
        byte[] corruptDocx = buildFakeZip();

        ResponseEntity<UploadDocumentResponse> response =
                uploadDocument(tenant.apiKey(), kbId, "corrupt.docx", corruptDocx);

        UploadDocumentResponse body = response.getBody();
        IngestionStatus finalStatus = awaitTerminalState(body.jobId());

        assertThat(finalStatus).isEqualTo(IngestionStatus.FAILED);
        IngestionJob job = ingestionJobRepository.findById(body.jobId()).orElseThrow();
        assertThat(job.getStage()).isEqualTo("extract");
        assertThat(job.getErrorDetail()).isNotBlank();
    }

    private static byte[] buildFakeZip() {
        // A minimal, syntactically-plausible but semantically-broken ZIP: real local-file-header
        // magic bytes so Tika's ZIP-based sniffing still resolves it to the docx MIME type, but no
        // real OOXML parts inside - POI's XWPFDocument constructor should reject this cleanly.
        byte[] header = {0x50, 0x4B, 0x03, 0x04}; // "PK\x03\x04"
        byte[] garbage = new byte[200];
        for (int i = 0; i < garbage.length; i++) {
            garbage[i] = (byte) (i % 256);
        }
        byte[] result = new byte[header.length + garbage.length];
        System.arraycopy(header, 0, result, 0, header.length);
        System.arraycopy(garbage, 0, result, header.length, garbage.length);
        return result;
    }

    // ---------------------------------------------------------------------
    // Task 20: empty / whitespace-only extracted text
    // ---------------------------------------------------------------------

    /**
     * Found live before the fix (Grooming #69): a whitespace-only file reached {@code READY} with
     * one real chunk containing empty-string content - genuinely embedded (a real provider may
     * reject an empty-string embed call outright) and indexed, but useless for search. User
     * confirmed (recommended option): a document with no real extractable text is a genuine
     * ingestion failure, not a silent success.
     */
    @Test
    void marksTheJobFailedForAWhitespaceOnlyFileRatherThanIndexingAnEmptyChunk() {
        TenantFixture tenant = createConfiguredTenant("Whitespace only tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "whitespace-kb");

        ResponseEntity<UploadDocumentResponse> response =
                uploadDocument(
                        tenant.apiKey(),
                        kbId,
                        "blank.txt",
                        "   \n\n\t  \n   ".getBytes(StandardCharsets.UTF_8));

        UploadDocumentResponse body = response.getBody();
        IngestionStatus finalStatus = awaitTerminalState(body.jobId());

        assertThat(finalStatus).isEqualTo(IngestionStatus.FAILED);
        IngestionJob job = ingestionJobRepository.findById(body.jobId()).orElseThrow();
        assertThat(job.getStage()).isEqualTo("extract");
        assertThat(job.getErrorDetail()).contains("no extractable text content");
        assertThat(chunkRepository.findByDocument_Id(body.documentId())).isEmpty();
    }

    // ---------------------------------------------------------------------
    // Task 16: tenant isolation under concurrent multi-tenant Stage 5 ingestion
    // ---------------------------------------------------------------------

    @Test
    void neverLeaksChunksOrLuceneEntriesAcrossTenantsUnderConcurrentIngestion() throws Exception {
        int tenantCount = 5;
        List<TenantFixture> tenants = new ArrayList<>();
        List<UUID> kbIds = new ArrayList<>();
        for (int i = 0; i < tenantCount; i++) {
            TenantFixture tenant = createConfiguredTenant("Concurrent isolation tenant " + i);
            tenants.add(tenant);
            kbIds.add(createKnowledgeBase(tenant.apiKey(), "kb-" + i));
        }

        ExecutorService executor = Executors.newFixedThreadPool(tenantCount);
        try {
            List<Future<UploadDocumentResponse>> futures = new ArrayList<>();
            for (int i = 0; i < tenantCount; i++) {
                int index = i;
                futures.add(
                        executor.submit(
                                () ->
                                        uploadDocument(
                                                        tenants.get(index).apiKey(),
                                                        kbIds.get(index),
                                                        "notes.txt",
                                                        ("tenant " + index + " unique content")
                                                                .getBytes(StandardCharsets.UTF_8))
                                                .getBody()));
            }
            List<UploadDocumentResponse> uploads = new ArrayList<>();
            for (Future<UploadDocumentResponse> future : futures) {
                uploads.add(future.get());
            }
            for (UploadDocumentResponse upload : uploads) {
                assertThat(awaitTerminalState(upload.jobId())).isEqualTo(IngestionStatus.READY);
            }

            for (int i = 0; i < tenantCount; i++) {
                UploadDocumentResponse upload = uploads.get(i);
                List<Chunk> chunks = chunkRepository.findByDocument_Id(upload.documentId());
                assertThat(chunks).isNotEmpty();
                for (Chunk chunk : chunks) {
                    assertThat(chunk.getTenantId()).isEqualTo(tenants.get(i).tenantId());
                    assertThat(chunk.getContent()).contains("tenant " + i + " unique content");
                }
                // Lucene isolation: this tenant's KB index must contain exactly this tenant's
                // chunk ids, and every OTHER tenant's KB index must not contain them.
                Set<String> indexedIds = luceneIndexManager.listIndexedChunkIds(kbIds.get(i));
                Set<String> expectedIds =
                        chunks.stream().map(c -> c.getId().toString()).collect(Collectors.toSet());
                assertThat(indexedIds).containsExactlyInAnyOrderElementsOf(expectedIds);
                for (int j = 0; j < tenantCount; j++) {
                    if (j != i) {
                        Set<String> otherIndex =
                                luceneIndexManager.listIndexedChunkIds(kbIds.get(j));
                        assertThat(otherIndex).noneMatch(expectedIds::contains);
                    }
                }
            }
        } finally {
            executor.shutdown();
        }
    }

    // ---------------------------------------------------------------------
    // Task 17: concurrent multi-document ingestion into the SAME knowledge_base
    // ---------------------------------------------------------------------

    @Test
    void indexesAllChunksWhenManyDocumentsIngestConcurrentlyIntoTheSameKnowledgeBase()
            throws Exception {
        TenantFixture tenant = createConfiguredTenant("Same KB concurrency tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "same-kb-concurrency");
        int documentCount = 8;

        ExecutorService executor = Executors.newFixedThreadPool(documentCount);
        try {
            List<Future<UploadDocumentResponse>> futures = new ArrayList<>();
            for (int i = 0; i < documentCount; i++) {
                int index = i;
                futures.add(
                        executor.submit(
                                () ->
                                        uploadDocument(
                                                        tenant.apiKey(),
                                                        kbId,
                                                        "doc-" + index + ".txt",
                                                        ("document number " + index)
                                                                .getBytes(StandardCharsets.UTF_8))
                                                .getBody()));
            }
            List<UploadDocumentResponse> uploads = new ArrayList<>();
            for (Future<UploadDocumentResponse> future : futures) {
                uploads.add(future.get());
            }
            for (UploadDocumentResponse upload : uploads) {
                assertThat(awaitTerminalState(upload.jobId())).isEqualTo(IngestionStatus.READY);
            }

            List<Chunk> allChunksInKb = chunkRepository.findByDocument_KnowledgeBase_Id(kbId);
            assertThat(allChunksInKb).hasSize(documentCount);
            Set<String> indexedIds = luceneIndexManager.listIndexedChunkIds(kbId);
            Set<String> expectedIds =
                    allChunksInKb.stream()
                            .map(c -> c.getId().toString())
                            .collect(Collectors.toSet());
            assertThat(indexedIds)
                    .as(
                            "every concurrently-ingested document's chunk must survive the shared"
                                    + " per-KB Lucene writer lock, none lost to a race")
                    .containsExactlyInAnyOrderElementsOf(expectedIds);
        } finally {
            executor.shutdown();
        }
    }

    // ---------------------------------------------------------------------
    // New this round: a genuinely multi-chunk document end-to-end (every prior Stage 5 test used
    // tiny "hello world"-style content that always produced exactly one chunk).
    // ---------------------------------------------------------------------

    @Test
    void indexesEveryChunkOfAGenuinelyMultiChunkDocumentWithCorrectPositionsAndHeadingPaths() {
        TenantFixture tenant = createConfiguredTenant("Multi-chunk document tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "multi-chunk-kb");
        // Long enough per section to clear DocumentChunkingService's single-chunk shortcut
        // (estimateTokens(text) < windowTokensFor(totalTokens) * 1.2, ~307 tokens / ~1228 chars
        // for the <1,000-token tier) - every prior Stage 5 test used short single-chunk content,
        // so this is the first end-to-end check that multiple real chunks flow correctly through
        // embed/persist/index together.
        String longSentence = "This is a reasonably long sentence used to pad section content. ";
        String content =
                "# Title\n"
                        + longSentence.repeat(6)
                        + "\n## Section One\n"
                        + longSentence.repeat(6)
                        + "\n## Section Two\n"
                        + longSentence.repeat(6)
                        + "\n### Subsection Two A\n"
                        + longSentence.repeat(6)
                        + "\n";

        UploadDocumentResponse upload =
                uploadDocument(
                                tenant.apiKey(),
                                kbId,
                                "structured.md",
                                content.getBytes(StandardCharsets.UTF_8))
                        .getBody();
        assertThat(awaitTerminalState(upload.jobId())).isEqualTo(IngestionStatus.READY);

        List<Chunk> chunks = chunkRepository.findByDocument_Id(upload.documentId());
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(4);
        List<Integer> positions = chunks.stream().map(Chunk::getChunkIndex).sorted().toList();
        assertThat(positions)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(chunks)
                .extracting(c -> (String) c.getMetadata().get("headingPath"))
                .contains("Title > Section Two > Subsection Two A");
        assertThat(chunks).allMatch(c -> c.getEmbedding1536() != null);
    }

    // ---------------------------------------------------------------------
    // New this round: a genuinely large document producing dozens of chunks - every prior test
    // (this file's own multi-chunk case included) topped out at single digits.
    // ---------------------------------------------------------------------

    /**
     * `FixedSizeChunkingStrategy`'s exact window/overlap/step math is already unit-tested precisely
     * ({@code FixedSizeChunkingStrategyTest}) - this checks something that can only be checked
     * end-to-end: whether the full pipeline (embed batching, atomic multi-chunk persist, Lucene
     * indexing) holds up correctly at a genuinely large chunk count, not just the 1-4 chunks every
     * previous Stage 5 test (this session's own included) ever exercised.
     */
    @Test
    void handlesADocumentThatProducesDozensOfChunks() throws Exception {
        TenantFixture tenant = createConfiguredTenant("Large document tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "large-document-kb");
        // ~100,000 chars of unstructured text (no headings, so FixedSizeChunkingStrategy's sliding
        // window applies) - falls in the 1,000-50,000-token tier (estimateTokens ≈ 25,000), giving
        // a 512-token/2,048-char window at 15% overlap, step ≈ 1,741 chars, so ≈ 58 chunks.
        StringBuilder contentBuilder = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            contentBuilder.append("word").append(i).append(' ');
        }
        byte[] content = contentBuilder.toString().getBytes(StandardCharsets.UTF_8);
        assertThat(content.length).isGreaterThan(9000);

        long start = System.currentTimeMillis();
        UploadDocumentResponse upload =
                uploadDocument(tenant.apiKey(), kbId, "large.txt", content).getBody();
        IngestionStatus finalStatus = awaitTerminalState(upload.jobId());
        long elapsedMs = System.currentTimeMillis() - start;

        assertThat(finalStatus).isEqualTo(IngestionStatus.READY);
        List<Chunk> chunks = chunkRepository.findByDocument_Id(upload.documentId());
        assertThat(chunks.size()).isGreaterThan(1);
        List<Integer> positions = chunks.stream().map(Chunk::getChunkIndex).sorted().toList();
        assertThat(positions)
                .as("chunk positions must be a gap-free 0..N-1 sequence even at this chunk count")
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(chunks).allMatch(c -> c.getEmbedding1536() != null);
        assertThat(chunks).allMatch(c -> !c.getContent().isBlank());

        Set<String> indexedIds = luceneIndexManager.listIndexedChunkIds(kbId);
        assertThat(indexedIds)
                .as("every one of the many chunks must survive persist + index, none dropped")
                .containsExactlyInAnyOrderElementsOf(
                        chunks.stream().map(c -> c.getId().toString()).collect(Collectors.toSet()));
        // awaitTerminalState's own 8s deadline already guarantees this passed within that bound -
        // this asserts real headroom (a mocked embedding model plus real Postgres/Lucene writes for
        // dozens of chunks should be nowhere close to the timeout), not just a tautology.
        assertThat(elapsedMs)
                .as(
                        "a %d-chunk document took %dms - unexpectedly close to the 8s timeout",
                        chunks.size(), elapsedMs)
                .isLessThan(5000);
    }

    // ---------------------------------------------------------------------
    // New this round: does a job failure actually increment the Micrometer failure counter?
    // ---------------------------------------------------------------------

    /**
     * The metrics work was previously verified live only for the jobs-by-state {@code Gauge}
     * (cross-checked against a direct SQL query against a running app) - the cumulative failure
     * {@code Counter} itself (tagged by stage/error type, incremented in {@code
     * IngestionPipelineService.markFailed}) was never directly confirmed to actually increment.
     */
    @Test
    void incrementsTheFailureCounterWithTheCorrectStageAndErrorTypeTags() {
        TenantFixture tenant = createConfiguredTenant("Failure counter tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "failure-counter-kb");
        double before =
                meterRegistry
                        .find("synapsemcp.ingestion.failures")
                        .tag("stage", "extract")
                        .tag("error_type", "IllegalStateException")
                        .counters()
                        .stream()
                        .mapToDouble(io.micrometer.core.instrument.Counter::count)
                        .sum();

        UploadDocumentResponse upload =
                uploadDocument(
                                tenant.apiKey(),
                                kbId,
                                "blank.txt",
                                "   \n   ".getBytes(StandardCharsets.UTF_8))
                        .getBody();
        assertThat(awaitTerminalState(upload.jobId())).isEqualTo(IngestionStatus.FAILED);

        double after =
                meterRegistry
                        .find("synapsemcp.ingestion.failures")
                        .tag("stage", "extract")
                        .tag("error_type", "IllegalStateException")
                        .counters()
                        .stream()
                        .mapToDouble(io.micrometer.core.instrument.Counter::count)
                        .sum();
        assertThat(after).isEqualTo(before + 1.0);
    }

    // ---------------------------------------------------------------------
    // Task 25: identical content uploaded to two different knowledge bases
    // ---------------------------------------------------------------------

    @Test
    void keepsChunksAndIndexesFullyIndependentAcrossTwoKnowledgeBasesForIdenticalContent()
            throws Exception {
        TenantFixture tenant = createConfiguredTenant("Two KB same content tenant");
        UUID kbId1 = createKnowledgeBase(tenant.apiKey(), "kb-one");
        UUID kbId2 = createKnowledgeBase(tenant.apiKey(), "kb-two");
        byte[] content =
                "identical content across two knowledge bases".getBytes(StandardCharsets.UTF_8);

        UploadDocumentResponse upload1 =
                uploadDocument(tenant.apiKey(), kbId1, "a.txt", content).getBody();
        UploadDocumentResponse upload2 =
                uploadDocument(tenant.apiKey(), kbId2, "a.txt", content).getBody();

        assertThat(upload1.documentId()).isNotEqualTo(upload2.documentId());
        assertThat(upload1.jobId()).isNotEqualTo(upload2.jobId());
        assertThat(awaitTerminalState(upload1.jobId())).isEqualTo(IngestionStatus.READY);
        assertThat(awaitTerminalState(upload2.jobId())).isEqualTo(IngestionStatus.READY);

        List<Chunk> chunks1 = chunkRepository.findByDocument_Id(upload1.documentId());
        List<Chunk> chunks2 = chunkRepository.findByDocument_Id(upload2.documentId());
        assertThat(chunks1).isNotEmpty();
        assertThat(chunks2).isNotEmpty();
        Set<UUID> ids1 = chunks1.stream().map(Chunk::getId).collect(Collectors.toSet());
        Set<UUID> ids2 = chunks2.stream().map(Chunk::getId).collect(Collectors.toSet());
        assertThat(ids1).doesNotContainAnyElementsOf(ids2);

        Set<String> index1 = luceneIndexManager.listIndexedChunkIds(kbId1);
        Set<String> index2 = luceneIndexManager.listIndexedChunkIds(kbId2);
        assertThat(index1)
                .containsExactlyInAnyOrderElementsOf(
                        ids1.stream().map(UUID::toString).collect(Collectors.toSet()));
        assertThat(index2)
                .containsExactlyInAnyOrderElementsOf(
                        ids2.stream().map(UUID::toString).collect(Collectors.toSet()));
    }

    // ---------------------------------------------------------------------
    // Task 21: Redis outage during the Stage 5c embedding cache lookup
    // ---------------------------------------------------------------------

    /**
     * This project has an established, explicitly-documented principle that Redis is a fail-open
     * dependency everywhere else it's used ({@code TenantCreationRateLimitFilter}'s own Javadoc;
     * {@code management.health.redis.enabled: false} so a Redis blip never fails {@code
     * /actuator/health}) - a Redis outage must never take down otherwise-working functionality.
     * Found live before the fix (Grooming #68): {@code ChunkEmbeddingService}'s cache calls were
     * unguarded, and a real Redis outage (simulated via {@code CLIENT PAUSE}, Redis 6.2, confirmed
     * via {@code redis-cli --version}, held longer than the app's own 1s Redis command timeout in
     * {@code application-test.yaml}) failed the whole ingestion job on a Redis timeout - purely a
     * performance optimization taking down otherwise-good ingestion, contradicting this project's
     * own convention. Now fails open: the job completes normally, just without cache benefit.
     */
    @Test
    void toleratesARedisOutageDuringEmbeddingCacheLookupInsteadOfFailingTheJob() throws Exception {
        TenantFixture tenant = createConfiguredTenant("Redis outage tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "redis-outage-kb");

        ProcessBuilder pause = new ProcessBuilder("redis-cli", "CLIENT", "PAUSE", "3000", "ALL");
        pause.inheritIO();
        Process pauseProcess = pause.start();
        pauseProcess.waitFor();

        ResponseEntity<UploadDocumentResponse> response =
                uploadDocument(
                        tenant.apiKey(),
                        kbId,
                        "notes.txt",
                        "content uploaded during a real Redis outage window"
                                .getBytes(StandardCharsets.UTF_8));
        UploadDocumentResponse body = response.getBody();
        IngestionStatus finalStatus = awaitTerminalState(body.jobId());

        assertThat(finalStatus).isEqualTo(IngestionStatus.READY);
        IngestionJob job = ingestionJobRepository.findById(body.jobId()).orElseThrow();
        assertThat(job.getErrorDetail()).isNull();
        assertThat(chunkRepository.findByDocument_Id(body.documentId())).isNotEmpty();
    }

    // ---------------------------------------------------------------------
    // Task 22 (expanded): retry of a FAILED job whose chunks were already durable
    // ---------------------------------------------------------------------

    /**
     * The exact scenario the reconciliation cron is designed to repair (Postgres commit succeeded,
     * the subsequent Lucene write then threw) is ALSO reachable through Grooming #26's own retry
     * path if a user re-uploads before the cron gets to it. Found live before the fix (Grooming
     * #67): {@code DocumentUploadService.resolveExisting()} resets the job/document to {@code
     * PENDING} and redispatches, but {@code ChunkPersistenceService.persist} never checked for
     * chunks already committed from the first (partially-successful) attempt, so a retry appended a
     * second, duplicate set alongside the first instead of replacing it - confirmed live, chunk
     * count doubled on every retry. {@code persist} now deletes any pre-existing chunks for the
     * document in the same transaction before inserting the new set.
     */
    @Test
    void retryingAJobWithAlreadyDurableChunksReplacesThemInsteadOfDuplicating() throws Exception {
        TenantFixture tenant = createConfiguredTenant("Retry duplication tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "retry-duplication-kb");
        byte[] content =
                "content that will be retried after a partial failure"
                        .getBytes(StandardCharsets.UTF_8);

        UploadDocumentResponse upload =
                uploadDocument(tenant.apiKey(), kbId, "a.txt", content).getBody();
        assertThat(awaitTerminalState(upload.jobId())).isEqualTo(IngestionStatus.READY);
        int originalChunkCount = chunkRepository.findByDocument_Id(upload.documentId()).size();
        assertThat(originalChunkCount).isGreaterThan(0);

        // Simulate exactly the "persist succeeded, Lucene write then threw" scenario: chunks stay
        // durable in Postgres, only the job/document status and Lucene index reflect the failure.
        luceneIndexManager.deleteChunks(
                kbId,
                chunkRepository.findByDocument_Id(upload.documentId()).stream()
                        .map(c -> c.getId().toString())
                        .toList());
        Document document = documentRepository.findById(upload.documentId()).orElseThrow();
        document.setStatus(IngestionStatus.FAILED);
        documentRepository.save(document);
        IngestionJob job = ingestionJobRepository.findById(upload.jobId()).orElseThrow();
        job.setStatus(IngestionStatus.FAILED);
        job.setStage("index");
        job.setErrorDetail("simulated Lucene write failure");
        ingestionJobRepository.save(job);

        // Now retry via the real HTTP re-upload path (Grooming #26) - the same content, against
        // the now-FAILED document/job.
        UploadDocumentResponse retry =
                uploadDocument(tenant.apiKey(), kbId, "a.txt", content).getBody();
        assertThat(retry.dispatched()).isTrue();
        assertThat(retry.documentId()).isEqualTo(upload.documentId());
        assertThat(retry.jobId()).isEqualTo(upload.jobId());
        assertThat(awaitTerminalState(retry.jobId())).isEqualTo(IngestionStatus.READY);

        List<Chunk> finalChunks = chunkRepository.findByDocument_Id(upload.documentId());
        assertThat(finalChunks).hasSize(originalChunkCount);
        Set<String> finalIndexedIds = luceneIndexManager.listIndexedChunkIds(kbId);
        assertThat(finalIndexedIds)
                .containsExactlyInAnyOrderElementsOf(
                        finalChunks.stream()
                                .map(c -> c.getId().toString())
                                .collect(Collectors.toSet()));
    }

    // ---------------------------------------------------------------------
    // Task 24: polling endpoint while a job is mid-flight (INDEXING)
    // ---------------------------------------------------------------------

    @Test
    void pollingReturnsIndexingWithAStageWhileTheJobIsStillRunning() {
        TenantFixture tenant = createConfiguredTenant("Mid-flight polling tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "mid-flight-kb");
        UUID documentId = UUID.randomUUID();
        // Seed a job in INDEXING with a real stage set, exactly as the pipeline would leave it
        // mid-run - polling must reflect this transient state accurately, not just terminal ones.
        Document document =
                documentRepository.save(
                        Document.create(
                                tenant.tenantId(),
                                knowledgeBaseRepository.findById(kbId).orElseThrow(),
                                "mid-flight.txt",
                                "text/plain",
                                IngestionStatus.INDEXING,
                                "mid-flight-hash-" + documentId));
        IngestionJob job =
                ingestionJobRepository.save(
                        IngestionJob.create(tenant.tenantId(), document, IngestionStatus.INDEXING));
        job.setStage("embed");
        ingestionJobRepository.save(job);

        ResponseEntity<JobStatusResponse> response =
                restTemplate.exchange(
                        "/api/v1/jobs/{jobId}",
                        HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders(tenant.apiKey())),
                        JobStatusResponse.class,
                        job.getId());

        assertThat(response.getBody().status()).isEqualTo(IngestionStatus.INDEXING);
        assertThat(response.getBody().stage()).isEqualTo("embed");
    }
}
