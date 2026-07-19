package com.synapsemcp.ingestion.reconcile;

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
import com.synapsemcp.ingestion.IngestionJob;
import com.synapsemcp.ingestion.IngestionJobRepository;
import com.synapsemcp.ingestion.index.LuceneIndexManager;
import com.synapsemcp.knowledgebase.CreateKnowledgeBaseRequest;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
import com.synapsemcp.rag.chunk.Chunk;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * rag_plan.md Stage 5d, Grooming #7: {@link IngestionReconciliationJob} is {@code @Scheduled}
 * hourly by default, so these tests call {@link IngestionReconciliationJob#reconcile()} directly
 * (autowired) rather than waiting for a real interval to elapse - exactly the same "call the bean
 * directly, no polling endpoint or scheduler wait needed" approach used for any other
 * not-time-triggerable-in-a-test background component in this codebase.
 */
class IngestionReconciliationJobIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private com.synapsemcp.common.RedisKeyPrefix redisKeyPrefix;
    @Autowired private IngestionJobRepository ingestionJobRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private ChunkRepository chunkRepository;
    @Autowired private LuceneIndexManager luceneIndexManager;
    @Autowired private IngestionReconciliationJob reconciliationJob;

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

    private void awaitTerminalState(UUID jobId) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            IngestionStatus status =
                    ingestionJobRepository.findById(jobId).orElseThrow().getStatus();
            if (status == IngestionStatus.READY || status == IngestionStatus.FAILED) {
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

    @Test
    void reindexesAChunkThatIsMissingFromLucene() throws Exception {
        TenantFixture tenant = createConfiguredTenant("Reconcile missing entry tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "reconcile-missing-kb");
        UUID jobId =
                uploadDocument(
                                tenant.apiKey(),
                                kbId,
                                "notes.txt",
                                "hello world".getBytes(StandardCharsets.UTF_8))
                        .getBody()
                        .jobId();
        awaitTerminalState(jobId);
        IngestionJob job = ingestionJobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(IngestionStatus.READY);

        List<Chunk> chunks = chunkRepository.findByDocument_KnowledgeBase_Id(kbId);
        assertThat(chunks).isNotEmpty();
        String chunkId = chunks.get(0).getId().toString();
        luceneIndexManager.deleteChunks(kbId, List.of(chunkId));
        assertThat(luceneIndexManager.listIndexedChunkIds(kbId)).doesNotContain(chunkId);

        reconciliationJob.reconcile();

        assertThat(luceneIndexManager.listIndexedChunkIds(kbId)).contains(chunkId);
    }

    @Test
    void deletesAnOrphanedLuceneEntryWithNoMatchingPostgresChunk() throws Exception {
        TenantFixture tenant = createConfiguredTenant("Reconcile orphan tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "reconcile-orphan-kb");
        // No document ever uploaded to this KB - any Lucene entry for it is definitionally
        // orphaned.
        UUID phantomChunkId = UUID.randomUUID();
        Chunk phantom = Chunk.create(tenant.tenantId(), kbId, null, 0, "orphaned content");
        setChunkId(phantom, phantomChunkId);
        luceneIndexManager.indexChunks(kbId, List.of(phantom));
        assertThat(luceneIndexManager.listIndexedChunkIds(kbId))
                .contains(phantomChunkId.toString());

        reconciliationJob.reconcile();

        assertThat(luceneIndexManager.listIndexedChunkIds(kbId))
                .doesNotContain(phantomChunkId.toString());
    }

    /**
     * Grooming #7's specific example: Postgres commit succeeded but the Lucene write then threw,
     * leaving a {@code FAILED} job for data that is actually durable and complete. Simulated here
     * by deleting the real chunks' Lucene entries after a genuine {@code READY} upload and manually
     * flipping the job back to {@code FAILED} - reconciliation must re-index them and flip the job
     * back to {@code READY}.
     */
    @Test
    void repairsAFailedJobWhoseChunksAreActuallyDurable() throws Exception {
        TenantFixture tenant = createConfiguredTenant("Reconcile failed job tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "reconcile-failed-kb");
        UploadDocumentResponse upload =
                uploadDocument(
                                tenant.apiKey(),
                                kbId,
                                "notes.txt",
                                "hello world".getBytes(StandardCharsets.UTF_8))
                        .getBody();
        awaitTerminalState(upload.jobId());
        assertThat(ingestionJobRepository.findById(upload.jobId()).orElseThrow().getStatus())
                .isEqualTo(IngestionStatus.READY);

        List<Chunk> chunks = chunkRepository.findByDocument_KnowledgeBase_Id(kbId);
        List<String> chunkIds = chunks.stream().map(c -> c.getId().toString()).toList();
        luceneIndexManager.deleteChunks(kbId, chunkIds);

        Document document = documentRepository.findById(upload.documentId()).orElseThrow();
        document.setStatus(IngestionStatus.FAILED);
        documentRepository.save(document);
        IngestionJob job = ingestionJobRepository.findById(upload.jobId()).orElseThrow();
        job.setStatus(IngestionStatus.FAILED);
        job.setStage("index");
        job.setErrorDetail("simulated Lucene write failure");
        ingestionJobRepository.save(job);

        reconciliationJob.reconcile();

        assertThat(luceneIndexManager.listIndexedChunkIds(kbId)).containsAll(chunkIds);
        IngestionJob repairedJob = ingestionJobRepository.findById(upload.jobId()).orElseThrow();
        assertThat(repairedJob.getStatus()).isEqualTo(IngestionStatus.READY);
        assertThat(repairedJob.getStage()).isNull();
        assertThat(repairedJob.getErrorDetail()).isNull();
        Document repairedDocument = documentRepository.findById(upload.documentId()).orElseThrow();
        assertThat(repairedDocument.getStatus()).isEqualTo(IngestionStatus.READY);
    }

    private static void setChunkId(Chunk chunk, UUID id) throws Exception {
        java.lang.reflect.Field field = Chunk.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(chunk, id);
    }
}
