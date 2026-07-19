package com.synapsemcp.knowledgebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.ProviderCredentials;
import com.synapsemcp.common.ProviderCredentialsCodec;
import com.synapsemcp.embedding.EmbeddingModelFactory;
import com.synapsemcp.ingestion.index.LuceneIndexManager;
import com.synapsemcp.tenant.ModelConfig;
import com.synapsemcp.tenant.ModelConfigRepository;
import com.synapsemcp.tenant.Tenant;
import com.synapsemcp.tenant.TenantRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class KnowledgeBaseServiceTest {

    private final KnowledgeBaseRepository knowledgeBaseRepository =
            mock(KnowledgeBaseRepository.class);
    private final KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository =
            mock(KnowledgeBaseModelConfigRepository.class);
    private final ModelConfigRepository modelConfigRepository = mock(ModelConfigRepository.class);
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final EmbeddingModelFactory embeddingModelFactory = mock(EmbeddingModelFactory.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final LuceneIndexManager luceneIndexManager = mock(LuceneIndexManager.class);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);

    private KnowledgeBaseService service;

    private final UUID tenantId = UUID.randomUUID();
    private Tenant tenant;
    private ModelConfig modelConfig;

    @BeforeEach
    void setUp() {
        service =
                new KnowledgeBaseService(
                        knowledgeBaseRepository,
                        knowledgeBaseModelConfigRepository,
                        modelConfigRepository,
                        tenantRepository,
                        embeddingModelFactory,
                        luceneIndexManager,
                        transactionManager);

        // TransactionTemplate.execute() needs a non-null TransactionStatus from getTransaction();
        // commit()/rollback() are Mockito void no-ops, matching a real commit with no side effects.
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(tenantId);

        modelConfig =
                ModelConfig.create(
                        tenant,
                        "openai",
                        "gpt-4o",
                        "openai",
                        "text-embedding-3-small",
                        ProviderCredentialsCodec.encode(
                                new ProviderCredentials("chat-key", "embed-key")));

        when(modelConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.of(modelConfig));
        when(embeddingModelFactory.getEmbeddingModel(tenantId)).thenReturn(embeddingModel);
        when(embeddingModel.dimensions()).thenReturn(1536);
        when(tenantRepository.lockById(tenantId)).thenReturn(Optional.of(tenant));
        when(knowledgeBaseRepository.countByTenant_Id(tenantId)).thenReturn(0L);
        when(knowledgeBaseRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsAKnowledgeBaseWithTheProbedDimensionAndAModelConfigSnapshot() {
        KnowledgeBaseResponse response =
                service.createKnowledgeBase(tenantId, new CreateKnowledgeBaseRequest("kb-1"));

        assertThat(response.name()).isEqualTo("kb-1");
        assertThat(response.embeddingDim()).isEqualTo(1536);
        verify(knowledgeBaseModelConfigRepository).save(any());
    }

    /**
     * Confirmed via `AskUserQuestion` (`plan.md` §9, 2026-07-17) - names are trimmed before
     * storage, so a whitespace-padded input doesn't create a visually-identical-but-distinct
     * knowledge base alongside an already-trimmed one.
     */
    @Test
    void trimsWhitespaceFromTheNameBeforeStoring() {
        KnowledgeBaseResponse response =
                service.createKnowledgeBase(
                        tenantId, new CreateKnowledgeBaseRequest("  padded-kb  "));

        assertThat(response.name()).isEqualTo("padded-kb");
    }

    @Test
    void throws422WhenNoModelConfigExistsForTenant() {
        when(modelConfigRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.createKnowledgeBase(
                                        tenantId, new CreateKnowledgeBaseRequest("kb-1")))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    /**
     * Found live (audit session, 2026-07-17): an earlier version of {@code probeEmbeddingDimension}
     * only wrapped {@code .dimensions()} in try/catch, not the preceding {@code
     * getEmbeddingModel()} call - and a missing-credential failure for a provider requiring one
     * (verified live: OpenAI's SDK) throws at <i>construction</i> time, not inside {@code
     * dimensions()}, letting it leak as an unhandled {@code 500} instead of this method's intended
     * {@code 422}.
     */
    @Test
    void throws422WhenBuildingTheEmbeddingClientItselfThrows() {
        when(embeddingModelFactory.getEmbeddingModel(tenantId))
                .thenThrow(
                        new IllegalStateException(
                                "At least one credential source must be specified"));

        assertThatThrownBy(
                        () ->
                                service.createKnowledgeBase(
                                        tenantId, new CreateKnowledgeBaseRequest("kb-1")))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void throws422WhenTheEmbeddingProbeCallThrows() {
        when(embeddingModel.dimensions()).thenThrow(new RuntimeException("provider unreachable"));

        assertThatThrownBy(
                        () ->
                                service.createKnowledgeBase(
                                        tenantId, new CreateKnowledgeBaseRequest("kb-1")))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void throws422WhenTheProbedDimensionIsUnsupported() {
        when(embeddingModel.dimensions()).thenReturn(999);

        assertThatThrownBy(
                        () ->
                                service.createKnowledgeBase(
                                        tenantId, new CreateKnowledgeBaseRequest("kb-1")))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void throws422WhenTenantAlreadyHasTenKnowledgeBases() {
        when(knowledgeBaseRepository.countByTenant_Id(tenantId)).thenReturn(10L);

        assertThatThrownBy(
                        () ->
                                service.createKnowledgeBase(
                                        tenantId, new CreateKnowledgeBaseRequest("kb-11")))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void locksTheTenantRowBeforeCountingExistingKnowledgeBases() {
        service.createKnowledgeBase(tenantId, new CreateKnowledgeBaseRequest("kb-1"));

        verify(tenantRepository).lockById(tenantId);
    }

    @Test
    void listReturnsOnlyTheAuthenticatedTenantsKnowledgeBases() {
        KnowledgeBase kb = KnowledgeBase.create(tenant, "kb-1", 1536);
        when(knowledgeBaseRepository.findAllByTenant_IdOrderByNameAsc(tenantId))
                .thenReturn(List.of(kb));

        List<KnowledgeBaseResponse> result = service.listKnowledgeBases(tenantId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("kb-1");
    }

    @Test
    void updateRenamesAnOwnedKnowledgeBase() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.create(tenant, "old-name", 1536);
        when(knowledgeBaseRepository.findByIdAndTenant_Id(kbId, tenantId))
                .thenReturn(Optional.of(kb));

        KnowledgeBaseResponse response =
                service.updateKnowledgeBase(
                        tenantId, kbId, new UpdateKnowledgeBaseRequest("new-name"));

        assertThat(response.name()).isEqualTo("new-name");
    }

    @Test
    void updateTrimsWhitespaceFromTheNameBeforeStoring() {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.create(tenant, "old-name", 1536);
        when(knowledgeBaseRepository.findByIdAndTenant_Id(kbId, tenantId))
                .thenReturn(Optional.of(kb));

        KnowledgeBaseResponse response =
                service.updateKnowledgeBase(
                        tenantId, kbId, new UpdateKnowledgeBaseRequest("  padded-new-name  "));

        assertThat(response.name()).isEqualTo("padded-new-name");
    }

    @Test
    void updateThrows404WhenTheKnowledgeBaseIsNotOwnedByTheCaller() {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseRepository.findByIdAndTenant_Id(kbId, tenantId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.updateKnowledgeBase(
                                        tenantId, kbId, new UpdateKnowledgeBaseRequest("x")))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void deleteRemovesAnOwnedKnowledgeBase() throws Exception {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.create(tenant, "kb-1", 1536);
        when(knowledgeBaseRepository.findByIdAndTenant_Id(kbId, tenantId))
                .thenReturn(Optional.of(kb));

        service.deleteKnowledgeBase(tenantId, kbId);

        verify(knowledgeBaseRepository).delete(kb);
        // Grooming #71: deleting a knowledge_base must also clean up its Lucene index directory -
        // found live that this was previously silently skipped, orphaning it on disk forever.
        verify(luceneIndexManager).deleteIndex(kbId);
    }

    /**
     * Grooming #71: a filesystem failure while cleaning up the Lucene index directory must not fail
     * the whole delete request - the knowledge_base is already gone from Postgres by that point, so
     * failing the response back to the caller would be misleading.
     */
    @Test
    void deleteSucceedsEvenWhenLuceneIndexCleanupFails() throws Exception {
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.create(tenant, "kb-1", 1536);
        when(knowledgeBaseRepository.findByIdAndTenant_Id(kbId, tenantId))
                .thenReturn(Optional.of(kb));
        org.mockito.Mockito.doThrow(new java.io.IOException("disk error"))
                .when(luceneIndexManager)
                .deleteIndex(kbId);

        service.deleteKnowledgeBase(tenantId, kbId);

        verify(knowledgeBaseRepository).delete(kb);
    }

    @Test
    void deleteThrows404WhenTheKnowledgeBaseIsNotOwnedByTheCaller() {
        UUID kbId = UUID.randomUUID();
        when(knowledgeBaseRepository.findByIdAndTenant_Id(kbId, tenantId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteKnowledgeBase(tenantId, kbId))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
