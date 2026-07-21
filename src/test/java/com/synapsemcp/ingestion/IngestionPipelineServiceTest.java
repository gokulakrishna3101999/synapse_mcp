package com.synapsemcp.ingestion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.document.Document;
import com.synapsemcp.document.DocumentRepository;
import com.synapsemcp.ingestion.chunk.ChunkingStrategy.ChunkData;
import com.synapsemcp.ingestion.chunk.DocumentChunkingService;
import com.synapsemcp.ingestion.embed.ChunkEmbeddingService;
import com.synapsemcp.ingestion.extract.DocumentExtractionService;
import com.synapsemcp.ingestion.extract.DocumentExtractor.ExtractionResult;
import com.synapsemcp.ingestion.index.LuceneIndexManager;
import com.synapsemcp.ingestion.metrics.IngestionMetrics;
import com.synapsemcp.ingestion.persist.ChunkPersistenceService;
import com.synapsemcp.knowledgebase.KnowledgeBase;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfigRepository;
import com.synapsemcp.knowledgebase.KnowledgeBaseRepository;
import com.synapsemcp.rag.chunk.Chunk;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * rag_plan.md Grooming #87/#88: {@code IngestionPipelineService} had no unit-level test coverage at
 * all before this - every existing test exercises it only through the full Spring context/real-
 * Postgres integration suite. Two guards added across those two rounds are covered here:
 *
 * <p>Grooming #87: confirmed live (a real Postgres row-lock timeout, not guessed) that a {@code
 * delete_knowledge_base} call landing between {@code ChunkPersistenceService.persist} committing
 * and {@code LuceneIndexManager.indexChunks} previously left a genuinely orphaned Lucene index
 * directory for a knowledge_base that no longer exists anywhere in Postgres - permanently
 * unreachable by {@code IngestionReconciliationJob}, which only ever iterates knowledge bases that
 * still exist. Fixed by re-checking {@code knowledgeBaseRepository.existsById(...)} immediately
 * before the Lucene write.
 *
 * <p>Grooming #88: found live that a delete landing at <em>any other</em> pipeline stage is already
 * caught by Hibernate's own stale-row detection on the very next {@code markStage}/{@code
 * markReady} call (a detached-entity {@code merge()} affecting zero rows, translated to {@link
 * ObjectOptimisticLockingFailureException} even with no explicit {@code @Version} column) - but
 * {@code markFailed}, called from the general catch clause, then threw the identical exception
 * trying to update the same already-gone row, escaping {@code run()} uncaught. Fixed with a
 * dedicated catch clause that logs cleanly and returns instead of ever attempting {@code
 * markFailed} for this specific exception type.
 *
 * <p>{@code PlatformTransactionManager} is mocked directly (not a real transaction) - {@code
 * TransactionTemplate.executeWithoutResult} only needs a {@code TransactionStatus} to hand to its
 * callback, and every collaborator the callback touches is itself already a plain mock, so no real
 * transactional semantics are needed for this test's purpose.
 */
class IngestionPipelineServiceTest {

    private final IngestionJobRepository ingestionJobRepository =
            mock(IngestionJobRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final KnowledgeBaseRepository knowledgeBaseRepository =
            mock(KnowledgeBaseRepository.class);
    private final KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository =
            mock(KnowledgeBaseModelConfigRepository.class);
    private final DocumentExtractionService extractionService =
            mock(DocumentExtractionService.class);
    private final DocumentChunkingService chunkingService = mock(DocumentChunkingService.class);
    private final ChunkEmbeddingService embeddingService = mock(ChunkEmbeddingService.class);
    private final ChunkPersistenceService persistenceService = mock(ChunkPersistenceService.class);
    private final LuceneIndexManager luceneIndexManager = mock(LuceneIndexManager.class);
    private final IngestionMetrics ingestionMetrics = mock(IngestionMetrics.class);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);

    private final IngestionPipelineService service =
            new IngestionPipelineService(
                    ingestionJobRepository,
                    documentRepository,
                    knowledgeBaseRepository,
                    knowledgeBaseModelConfigRepository,
                    extractionService,
                    chunkingService,
                    embeddingService,
                    persistenceService,
                    luceneIndexManager,
                    ingestionMetrics,
                    transactionManager);

    private UUID jobId;
    private UUID documentId;
    private UUID knowledgeBaseId;

    @BeforeEach
    void stubCommonPipelineStages() throws Exception {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        jobId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        knowledgeBaseId = UUID.randomUUID();

        IngestionJob job = mock(IngestionJob.class);
        when(job.getDocumentId()).thenReturn(documentId);
        when(ingestionJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        Document document = mock(Document.class);
        when(document.getKnowledgeBaseId()).thenReturn(knowledgeBaseId);
        when(document.getFileType()).thenReturn("text/plain");
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        KnowledgeBase knowledgeBase = mock(KnowledgeBase.class);
        when(knowledgeBase.getEmbeddingDim()).thenReturn(1536);
        when(knowledgeBaseRepository.findById(knowledgeBaseId))
                .thenReturn(Optional.of(knowledgeBase));

        KnowledgeBaseModelConfig kbConfig = mock(KnowledgeBaseModelConfig.class);
        when(knowledgeBaseModelConfigRepository.findByKnowledgeBase_Id(knowledgeBaseId))
                .thenReturn(Optional.of(kbConfig));

        when(extractionService.extract(any(), any(), any()))
                .thenReturn(new ExtractionResult("hello world", "plain-text"));
        when(chunkingService.chunk(any(), any()))
                .thenReturn(List.of(new ChunkData("hello world", 0, null, "plain-text")));
        when(embeddingService.embed(any(), any())).thenReturn(List.of(new float[1536]));

        Chunk chunk = mock(Chunk.class);
        when(persistenceService.persist(any(), eq(knowledgeBaseId), any(), any(), any(), eq(1536)))
                .thenReturn(new ChunkPersistenceService.PersistResult(List.of(chunk), List.of()));
    }

    @Test
    void skipsTheLuceneWriteAndAnyFurtherStateChangeWhenTheKnowledgeBaseWasDeletedMidPipeline()
            throws Exception {
        // The critical setup: by the time run() reaches its post-persist guard, the
        // knowledge_base no longer exists - a concurrent delete raced in between persist()
        // committing (already stubbed as successful above) and this check.
        when(knowledgeBaseRepository.existsById(knowledgeBaseId)).thenReturn(false);

        service.run(jobId, "hello world".getBytes(StandardCharsets.UTF_8));

        verify(luceneIndexManager, never()).indexChunks(any(), any());
        verify(luceneIndexManager, never()).deleteChunks(any(), any());
        // transitionToIndexing (unconditional, right at the start of run()) is the only place
        // that touches documentRepository.save(...) before this new guard - markReady/markFailed
        // would each add one more call, so exactly one confirms neither of those ran, i.e. this
        // method didn't attempt to resurrect a document row that's already gone in the real
        // system by this point.
        verify(documentRepository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void proceedsWithTheLuceneWriteAndMarksReadyWhenTheKnowledgeBaseStillExists() throws Exception {
        when(knowledgeBaseRepository.existsById(knowledgeBaseId)).thenReturn(true);

        service.run(jobId, "hello world".getBytes(StandardCharsets.UTF_8));

        verify(luceneIndexManager).indexChunks(eq(knowledgeBaseId), any());
        verify(ingestionJobRepository, org.mockito.Mockito.atLeastOnce()).save(any());
        verify(documentRepository, org.mockito.Mockito.atLeastOnce()).save(any());
    }

    /**
     * Grooming #88: a delete landing at any stage before the Grooming #87 guard surfaces as {@link
     * ObjectOptimisticLockingFailureException} from whichever {@code markStage}/{@code
     * transitionToIndexing} save runs next (Hibernate's own stale-row detection) - reproduced here
     * by making {@code transitionToIndexing}'s own {@code documentRepository.save(...)} throw it,
     * the earliest point in {@code run()} this exception could occur. The critical property: this
     * must never propagate out of {@code run()} (an {@code @Async void} method has no caller to
     * report a failure to - letting it escape means the executor's generic uncaught-exception
     * handler is the only place it's ever visible), and {@code markFailed} must never be attempted
     * either, since its own save calls would throw the identical exception against the same
     * already-gone row.
     */
    @Test
    void abandonsCleanlyWithoutAttemptingMarkFailedWhenARowIsAlreadyGone() throws Exception {
        when(documentRepository.save(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Document.class, documentId));

        service.run(jobId, "hello world".getBytes(StandardCharsets.UTF_8));

        verify(persistenceService, never())
                .persist(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(luceneIndexManager, never()).indexChunks(any(), any());
        // Exactly the one (failing) attempt - markFailed would add a second documentRepository
        // .save(...) call if it were ever reached.
        verify(documentRepository, org.mockito.Mockito.times(1)).save(any());
    }
}
