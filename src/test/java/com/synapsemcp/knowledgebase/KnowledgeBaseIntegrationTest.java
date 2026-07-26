package com.synapsemcp.knowledgebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.AbstractIntegrationTest;
import com.synapsemcp.common.IngestionStatus;
import com.synapsemcp.common.RedisKeyPrefix;
import com.synapsemcp.document.Document;
import com.synapsemcp.embedding.EmbeddingModelFactory;
import com.synapsemcp.ingestion.IngestionJob;
import com.synapsemcp.rag.chunk.Chunk;
import com.synapsemcp.tenant.ConfigureModelRequest;
import com.synapsemcp.tenant.CreateTenantRequest;
import com.synapsemcp.tenant.CreateTenantResponse;
import com.synapsemcp.tenant.ModelConfigResponse;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * No live embedding provider is available in this environment (same blocker as every prior stage) -
 * {@link EmbeddingModelFactory} is replaced with a {@code @MockitoBean} so the dimension-derivation
 * probe (rag_plan.md Stage 3, Grooming #29) never makes a real network call.
 */
class KnowledgeBaseIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private EntityManager entityManager;

    @Autowired private PlatformTransactionManager transactionManager;

    @Autowired private StringRedisTemplate redisTemplate;

    @Autowired private RedisKeyPrefix redisKeyPrefix;

    @MockitoBean private EmbeddingModelFactory embeddingModelFactory;

    private EmbeddingModel embeddingModel;

    private record TenantFixture(UUID tenantId, String apiKey) {}

    @BeforeEach
    void stubEmbeddingModel() {
        embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.dimensions()).thenReturn(1536);
        when(embeddingModelFactory.getEmbeddingModel(any())).thenReturn(embeddingModel);
    }

    /**
     * Clears the tenant-creation rate-limit key before every call so a single test method creating
     * more than 5 tenants (e.g. the multi-tenant concurrency test below) doesn't spuriously hit the
     * real 5/hour/IP limit - all {@code TestRestTemplate} calls originate from the same loopback
     * address, same reasoning as {@code AbstractIntegrationTest}'s own per-test-method reset, just
     * needed mid-test here instead.
     */
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

    private HttpHeaders bearerHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        return headers;
    }

    private ResponseEntity<KnowledgeBaseResponse> create(String apiKey, String name) {
        return restTemplate.exchange(
                "/api/v1/knowledgebase",
                HttpMethod.POST,
                new HttpEntity<>(new CreateKnowledgeBaseRequest(name), bearerHeaders(apiKey)),
                KnowledgeBaseResponse.class);
    }

    private ResponseEntity<String> createRaw(String apiKey, String name) {
        return restTemplate.exchange(
                "/api/v1/knowledgebase",
                HttpMethod.POST,
                new HttpEntity<>(new CreateKnowledgeBaseRequest(name), bearerHeaders(apiKey)),
                String.class);
    }

    @Test
    void createsAKnowledgeBaseWithTheDerivedDimension() {
        TenantFixture tenant = createConfiguredTenant("KB create tenant");

        ResponseEntity<KnowledgeBaseResponse> response = create(tenant.apiKey(), "kb-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        KnowledgeBaseResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.name()).isEqualTo("kb-1");
        assertThat(body.embeddingDim()).isEqualTo(1536);
    }

    @Test
    void create422sWhenNoModelConfigExists() {
        CreateTenantResponse tenant =
                restTemplate.postForObject(
                        "/api/v1/tenants",
                        new CreateTenantRequest("Unconfigured tenant"),
                        CreateTenantResponse.class);

        ResponseEntity<String> response = createRaw(tenant.apiKey(), "kb-1");

        assertThat(response.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void create422sWhenTheProbedDimensionIsUnsupported() {
        when(embeddingModel.dimensions()).thenReturn(999);
        TenantFixture tenant = createConfiguredTenant("Unsupported dim tenant");

        ResponseEntity<String> response = createRaw(tenant.apiKey(), "kb-1");

        assertThat(response.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void create422sWhenTheEmbeddingProbeCallThrows() {
        when(embeddingModel.dimensions()).thenThrow(new RuntimeException("provider unreachable"));
        TenantFixture tenant = createConfiguredTenant("Probe failure tenant");

        ResponseEntity<String> response = createRaw(tenant.apiKey(), "kb-1");

        assertThat(response.getStatusCode().value()).isEqualTo(422);
    }

    /**
     * Found live (audit session, 2026-07-17): a construction-time failure building the embedding
     * client (distinct from a runtime failure inside {@code .dimensions()}, the scenario above)
     * used to leak as an unhandled {@code 500} - reproduced live with a real provider missing
     * credentials before being fixed.
     */
    @Test
    void create422sWhenBuildingTheEmbeddingClientItselfThrows() {
        when(embeddingModelFactory.getEmbeddingModel(any()))
                .thenThrow(
                        new IllegalStateException(
                                "At least one credential source must be specified"));
        TenantFixture tenant = createConfiguredTenant("Client construction failure tenant");

        ResponseEntity<String> response = createRaw(tenant.apiKey(), "kb-1");

        assertThat(response.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void duplicateNameWithinTheSameTenantReturns409() {
        TenantFixture tenant = createConfiguredTenant("Duplicate name tenant");
        create(tenant.apiKey(), "duplicate-name");

        ResponseEntity<String> response = createRaw(tenant.apiKey(), "duplicate-name");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    /**
     * Confirmed via `AskUserQuestion` (`plan.md` §9, 2026-07-17): case-variant names are treated as
     * duplicates, not distinct knowledge bases, enforced by a case-insensitive functional unique
     * index ({@code uq_knowledge_bases_tenant_name_ci}, applied via {@code schema.sql}) rather than
     * the plain JPA-level constraint this replaced.
     */
    @Test
    void caseVariantNameWithinTheSameTenantReturns409() {
        TenantFixture tenant = createConfiguredTenant("Case-insensitive dup tenant");
        create(tenant.apiKey(), "My Knowledge Base");

        ResponseEntity<String> response = createRaw(tenant.apiKey(), "MY KNOWLEDGE BASE");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    /**
     * Confirmed via `AskUserQuestion` (`plan.md` §9, 2026-07-17): names are trimmed before storage
     * and before the uniqueness check, so a whitespace-padded name is rejected as a duplicate of
     * the untrimmed original rather than silently creating a visually-identical second knowledge
     * base.
     */
    @Test
    void whitespacePaddedNameIsTrimmedAndTreatedAsADuplicate() {
        TenantFixture tenant = createConfiguredTenant("Trim dup tenant");
        create(tenant.apiKey(), "padded-name");

        ResponseEntity<String> response = createRaw(tenant.apiKey(), "  padded-name  ");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void whitespacePaddedNameIsStoredTrimmed() {
        TenantFixture tenant = createConfiguredTenant("Trim storage tenant");

        ResponseEntity<KnowledgeBaseResponse> response = create(tenant.apiKey(), "  trimmed-kb  ");

        assertThat(response.getBody().name()).isEqualTo("trimmed-kb");
    }

    @Test
    void listReturnsOnlyTheAuthenticatedTenantsKnowledgeBases() {
        TenantFixture tenantA = createConfiguredTenant("List tenant A");
        TenantFixture tenantB = createConfiguredTenant("List tenant B");
        create(tenantA.apiKey(), "a-kb");
        create(tenantB.apiKey(), "b-kb");

        ResponseEntity<KnowledgeBaseResponse[]> response =
                restTemplate.exchange(
                        "/api/v1/knowledgebase",
                        HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders(tenantA.apiKey())),
                        KnowledgeBaseResponse[].class);

        assertThat(response.getBody())
                .extracting(KnowledgeBaseResponse::name)
                .containsExactly("a-kb");
    }

    /**
     * Session 15 stress-tested tenant isolation under real concurrent load for the model-config
     * endpoints (150 requests / 15 tenants) but never for these new Stage 3 knowledge_base
     * endpoints - found during a later audit pass. Same method: N tenants, each with one
     * uniquely-named KB, fired as concurrent list requests randomly interleaved (not sequential
     * per-tenant batches), asserting every response contains only that caller's own data.
     */
    @Test
    void listNeverLeaksAcrossTenantsUnderConcurrentMultiTenantLoad() throws Exception {
        int tenantCount = 12;
        List<TenantFixture> tenants = new ArrayList<>();
        for (int i = 0; i < tenantCount; i++) {
            TenantFixture tenant = createConfiguredTenant("Concurrent isolation tenant " + i);
            create(tenant.apiKey(), "kb-owned-by-tenant-" + i);
            tenants.add(tenant);
        }

        int requestCount = 80;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                int tenantIndex = i % tenantCount;
                TenantFixture tenant = tenants.get(tenantIndex);
                futures.add(
                        executor.submit(
                                () -> {
                                    ResponseEntity<KnowledgeBaseResponse[]> response =
                                            restTemplate.exchange(
                                                    "/api/v1/knowledgebase",
                                                    HttpMethod.GET,
                                                    new HttpEntity<>(
                                                            bearerHeaders(tenant.apiKey())),
                                                    KnowledgeBaseResponse[].class);
                                    KnowledgeBaseResponse[] body = response.getBody();
                                    return body != null
                                            && body.length == 1
                                            && body[0].name()
                                                    .equals("kb-owned-by-tenant-" + tenantIndex);
                                }));
            }
            for (Future<Boolean> future : futures) {
                assertThat(future.get())
                        .as("response must contain only the caller's own KB")
                        .isTrue();
            }
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void updateRenamesAnOwnedKnowledgeBase() {
        TenantFixture tenant = createConfiguredTenant("Update tenant");
        UUID kbId = create(tenant.apiKey(), "old-name").getBody().id();

        ResponseEntity<KnowledgeBaseResponse> response =
                restTemplate.exchange(
                        "/api/v1/knowledgebase/{id}",
                        HttpMethod.PUT,
                        new HttpEntity<>(
                                new UpdateKnowledgeBaseRequest("new-name"),
                                bearerHeaders(tenant.apiKey())),
                        KnowledgeBaseResponse.class,
                        kbId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().name()).isEqualTo("new-name");
    }

    @Test
    void updateReturns404ForAnotherTenantsKnowledgeBase() {
        TenantFixture tenantA = createConfiguredTenant("Update isolation tenant A");
        TenantFixture tenantB = createConfiguredTenant("Update isolation tenant B");
        UUID kbId = create(tenantA.apiKey(), "a-kb").getBody().id();

        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/api/v1/knowledgebase/{id}",
                        HttpMethod.PUT,
                        new HttpEntity<>(
                                new UpdateKnowledgeBaseRequest("hijacked"),
                                bearerHeaders(tenantB.apiKey())),
                        String.class,
                        kbId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Extended beyond Session 17's original version (found during a later audit pass): that test
     * only verified the {@code Document} row disappeared, never the {@code Chunk}/{@code
     * IngestionJob} rows two levels down - the actual point of the cascade fix (Document.java
     * gaining {@code @OnDelete(CASCADE)}) was that those already cascade FROM documents, so a KB
     * delete should remove the *entire* tree in one statement. Also verifies the {@code
     * KnowledgeBaseModelConfig} snapshot itself is gone (it already had {@code @OnDelete(CASCADE)}
     * from Stage 0.5, but this was never actually exercised by a live test either).
     */
    @Test
    void deleteCascadesToDocumentsChunksIngestionJobsAndTheModelConfigSnapshot() {
        TenantFixture tenant = createConfiguredTenant("Delete cascade tenant");
        UUID kbId = create(tenant.apiKey(), "cascade-kb").getBody().id();
        CascadeFixtureIds ids = persistDocumentWithChunkAndJobUnder(tenant.tenantId(), kbId);
        assertThat(findKnowledgeBaseModelConfigByKnowledgeBaseId(kbId)).isNotNull();

        ResponseEntity<Void> response =
                restTemplate.exchange(
                        "/api/v1/knowledgebase/{id}",
                        HttpMethod.DELETE,
                        new HttpEntity<>(bearerHeaders(tenant.apiKey())),
                        Void.class,
                        kbId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(findById(Document.class, ids.documentId())).isNull();
        assertThat(findById(Chunk.class, ids.chunkId())).isNull();
        assertThat(findById(IngestionJob.class, ids.ingestionJobId())).isNull();
        assertThat(findKnowledgeBaseModelConfigByKnowledgeBaseId(kbId)).isNull();
    }

    @Test
    void deleteReturns404ForAnotherTenantsKnowledgeBase() {
        TenantFixture tenantA = createConfiguredTenant("Delete isolation tenant A");
        TenantFixture tenantB = createConfiguredTenant("Delete isolation tenant B");
        UUID kbId = create(tenantA.apiKey(), "a-kb").getBody().id();

        ResponseEntity<String> response =
                restTemplate.exchange(
                        "/api/v1/knowledgebase/{id}",
                        HttpMethod.DELETE,
                        new HttpEntity<>(bearerHeaders(tenantB.apiKey())),
                        String.class,
                        kbId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void eleventhKnowledgeBaseCreationReturns422() {
        TenantFixture tenant = createConfiguredTenant("Limit tenant");
        for (int i = 0; i < 10; i++) {
            ResponseEntity<KnowledgeBaseResponse> response = create(tenant.apiKey(), "kb-" + i);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<String> eleventh = createRaw(tenant.apiKey(), "kb-11");

        assertThat(eleventh.getStatusCode().value()).isEqualTo(422);
    }

    /**
     * Found-bug-class regression test, same shape as the model_configs concurrent-write race fixed
     * in an earlier audit session: without the pessimistic tenant-row lock in {@code
     * KnowledgeBaseService.createKnowledgeBase}, concurrent creates near the limit could race past
     * 10. Fires more concurrent requests than the limit allows and asserts the count never exceeds
     * it.
     */
    @Test
    void concurrentKnowledgeBaseCreationNeverExceedsTheLimit() throws Exception {
        TenantFixture tenant = createConfiguredTenant("Concurrent limit tenant");
        int concurrency = 15;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                int index = i;
                futures.add(
                        executor.submit(
                                () -> createRaw(tenant.apiKey(), "concurrent-kb-" + index)));
            }
            long successCount = 0;
            for (Future<ResponseEntity<String>> future : futures) {
                int status = future.get().getStatusCode().value();
                assertThat(status).isIn(201, 422);
                if (status == 201) {
                    successCount++;
                }
            }
            assertThat(successCount).isEqualTo(10);
        } finally {
            executor.shutdown();
        }
    }

    /**
     * User-requested (2026-07-22): duplicate-name rejection must be concurrency-proof, not just
     * checked-then-raced. Fires many concurrent creates for the same tenant with the identical name
     * - exactly one must win with {@code 201}, every other must get a clean {@code 409}, never a
     * raw database error, and only one row for that name may ever exist afterward.
     */
    @Test
    void concurrentKnowledgeBaseCreationWithTheSameNameOnlyOneSucceeds() throws Exception {
        TenantFixture tenant = createConfiguredTenant("Concurrent duplicate name tenant");
        int concurrency = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(executor.submit(() -> createRaw(tenant.apiKey(), "same-name-race")));
            }
            long successCount = 0;
            for (Future<ResponseEntity<String>> future : futures) {
                int status = future.get().getStatusCode().value();
                assertThat(status).isIn(201, 409);
                if (status == 201) {
                    successCount++;
                }
            }
            assertThat(successCount).isEqualTo(1);
        } finally {
            executor.shutdown();
        }

        ResponseEntity<KnowledgeBaseResponse[]> listResponse =
                restTemplate.exchange(
                        "/api/v1/knowledgebase",
                        HttpMethod.GET,
                        new HttpEntity<>(bearerHeaders(tenant.apiKey())),
                        KnowledgeBaseResponse[].class);
        assertThat(List.of(listResponse.getBody()))
                .filteredOn(kb -> kb.name().equalsIgnoreCase("same-name-race"))
                .hasSize(1);
    }

    private record CascadeFixtureIds(UUID documentId, UUID chunkId, UUID ingestionJobId) {}

    /**
     * Commits immediately, mirroring {@code ModelConfigIntegrationTest}'s direct-persist helper -
     * needed since no {@code DocumentRepository}/{@code ChunkRepository}/{@code
     * IngestionJobRepository} exist yet (Stage 4/5 work). Persists one row two levels deep (a
     * {@code Chunk} and an {@code IngestionJob}, both hanging off the {@code Document}) so the
     * cascade-delete test below actually exercises the transitive cascade, not just the first
     * level.
     */
    private CascadeFixtureIds persistDocumentWithChunkAndJobUnder(
            UUID tenantId, UUID knowledgeBaseId) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return transactionTemplate.execute(
                status -> {
                    KnowledgeBase knowledgeBase =
                            entityManager.getReference(KnowledgeBase.class, knowledgeBaseId);
                    Document document =
                            Document.create(
                                    tenantId,
                                    knowledgeBase,
                                    "file.txt",
                                    "text/plain",
                                    IngestionStatus.READY,
                                    "hash-1");
                    entityManager.persist(document);
                    Chunk chunk =
                            Chunk.create(tenantId, knowledgeBaseId, document, 0, "chunk content");
                    entityManager.persist(chunk);
                    IngestionJob job =
                            IngestionJob.create(tenantId, document, IngestionStatus.READY);
                    entityManager.persist(job);
                    entityManager.flush();
                    return new CascadeFixtureIds(document.getId(), chunk.getId(), job.getId());
                });
    }

    private KnowledgeBaseModelConfig findKnowledgeBaseModelConfigByKnowledgeBaseId(
            UUID knowledgeBaseId) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return transactionTemplate.execute(
                status ->
                        entityManager
                                .createQuery(
                                        "SELECT c FROM KnowledgeBaseModelConfig c "
                                                + "WHERE c.knowledgeBase.id = :kbId",
                                        KnowledgeBaseModelConfig.class)
                                .setParameter("kbId", knowledgeBaseId)
                                .getResultStream()
                                .findFirst()
                                .orElse(null));
    }

    private <T> T findById(Class<T> type, UUID id) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return transactionTemplate.execute(status -> entityManager.find(type, id));
    }
}
