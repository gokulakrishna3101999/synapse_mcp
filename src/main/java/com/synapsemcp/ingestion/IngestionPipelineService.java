package com.synapsemcp.ingestion;

import com.synapsemcp.common.IngestionStatus;
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
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The async dispatch target Stage 4's upload flow fires onto once a {@code Document}/{@code
 * IngestionJob} pair is committed (rag_plan.md Stage 5): {@code extract → chunk → embed → persist →
 * index → READY}. Runs on the {@code ingestionExecutor} bean ({@link
 * com.synapsemcp.config.AsyncConfig}, Grooming #9), which propagates {@code TenantContext} and the
 * correlation-id MDC entry in ({@link com.synapsemcp.common.ContextPropagatingTaskDecorator}).
 *
 * <p>{@code content} is passed in directly from the still-in-memory upload request rather than read
 * back from any durable store - Stage 4 never persists the raw file bytes anywhere (user confirmed,
 * `plan.md` §9 2026-07-17: no blob-storage layer exists or is planned; a {@code FAILED} job is
 * already retried by re-uploading the identical file, per Grooming #26, so this is consistent with
 * the plan's existing hash-based idempotency design rather than a new gap). This does mean a job's
 * content is lost if the JVM restarts between the upload committing and this method actually
 * running - the job then stays {@code PENDING} until the client re-uploads.
 *
 * <p>Job state machine (Grooming #30): {@code PENDING → INDEXING → READY | FAILED} on {@code
 * ingestion_jobs}, with {@code documents.status} mirrored to the identical value in the same
 * transaction at every transition. {@code ingestion_jobs.stage} records the pipeline stage
 * in-flight (for observability, and so a failure records exactly where it happened) - {@code null}
 * while healthy, cleared again on success.
 *
 * <p>Two extractors are deferred to a follow-up session (user confirmed, `plan.md` §9 2026-07-17):
 * {@code PdfExtractor} (LLM vision) and {@code ImageExtractor} (LLM vision + RapidOCR). A document
 * whose MIME type only they would support currently fails at the {@code extract} stage via {@link
 * DocumentExtractionService}'s {@code UnsupportedOperationException} - caught by this method like
 * any other stage failure, recorded to {@code error_detail}, job {@code FAILED}. This is the
 * pipeline's ordinary error contract, not special-cased.
 */
@Service
public class IngestionPipelineService {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipelineService.class);

    private static final String STAGE_EXTRACT = "extract";
    private static final String STAGE_CHUNK = "chunk";
    private static final String STAGE_EMBED = "embed";
    private static final String STAGE_PERSIST = "persist";
    private static final String STAGE_INDEX = "index";

    private final IngestionJobRepository ingestionJobRepository;
    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository;
    private final DocumentExtractionService extractionService;
    private final DocumentChunkingService chunkingService;
    private final ChunkEmbeddingService embeddingService;
    private final ChunkPersistenceService persistenceService;
    private final LuceneIndexManager luceneIndexManager;
    private final IngestionMetrics ingestionMetrics;
    private final TransactionTemplate transactionTemplate;

    IngestionPipelineService(
            IngestionJobRepository ingestionJobRepository,
            DocumentRepository documentRepository,
            KnowledgeBaseRepository knowledgeBaseRepository,
            KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository,
            DocumentExtractionService extractionService,
            DocumentChunkingService chunkingService,
            ChunkEmbeddingService embeddingService,
            ChunkPersistenceService persistenceService,
            LuceneIndexManager luceneIndexManager,
            IngestionMetrics ingestionMetrics,
            PlatformTransactionManager transactionManager) {
        this.ingestionJobRepository = ingestionJobRepository;
        this.documentRepository = documentRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeBaseModelConfigRepository = knowledgeBaseModelConfigRepository;
        this.extractionService = extractionService;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.persistenceService = persistenceService;
        this.luceneIndexManager = luceneIndexManager;
        this.ingestionMetrics = ingestionMetrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Async("ingestionExecutor")
    public void run(UUID jobId, byte[] content) {
        IngestionJob job = ingestionJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.error("ingestion job {} not found - dropping", jobId);
            return;
        }
        // job.getDocument()/document.getKnowledgeBase() are lazy associations whose owning
        // Hibernate session closes with each individual repository call - re-fetched directly
        // here (safe as detached entities across the later per-stage transactions below) rather
        // than navigated lazily, which would throw LazyInitializationException the first time a
        // later stage, running in a brand new transaction, touched one (found live against a real
        // Postgres instance, `plan.md` §9 2026-07-17).
        UUID documentId = job.getDocumentId();
        Document document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            log.error(
                    "ingestion job {} references missing document {} - dropping",
                    jobId,
                    documentId);
            return;
        }
        UUID knowledgeBaseId = document.getKnowledgeBaseId();

        try {
            transitionToIndexing(job, document);

            KnowledgeBase knowledgeBase =
                    knowledgeBaseRepository
                            .findById(knowledgeBaseId)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "knowledge_base "
                                                            + knowledgeBaseId
                                                            + " not found"));
            KnowledgeBaseModelConfig kbConfig =
                    knowledgeBaseModelConfigRepository
                            .findByKnowledgeBase_Id(knowledgeBaseId)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "knowledge_base "
                                                            + knowledgeBaseId
                                                            + " has no"
                                                            + " knowledge_base_model_configs"
                                                            + " row"));

            markStage(job, STAGE_EXTRACT);
            ExtractionResult extraction =
                    extractionService.extract(content, document.getFileType(), kbConfig);
            if (extraction.text().isBlank()) {
                // User confirmed (recommended option, `plan.md` §9 2026-07-17, Grooming #69): a
                // degenerate document with no real extractable text is a genuine ingestion
                // failure, not a silently-successful job containing one meaningless
                // empty-content chunk (found live - it would still get embedded and indexed,
                // and a real provider may even reject an empty-string embed call outright).
                throw new IllegalStateException("no extractable text content");
            }

            markStage(job, STAGE_CHUNK);
            List<ChunkData> chunkDataList =
                    chunkingService.chunk(extraction.text(), document.getFileType());

            markStage(job, STAGE_EMBED);
            List<String> texts = chunkDataList.stream().map(ChunkData::content).toList();
            List<float[]> embeddings = embeddingService.embed(kbConfig, texts);

            markStage(job, STAGE_PERSIST);
            ChunkPersistenceService.PersistResult persistResult =
                    persistenceService.persist(
                            document,
                            knowledgeBaseId,
                            extraction.extractorName(),
                            chunkDataList,
                            embeddings,
                            knowledgeBase.getEmbeddingDim());

            markStage(job, STAGE_INDEX);
            if (!persistResult.replacedChunkIds().isEmpty()) {
                luceneIndexManager.deleteChunks(knowledgeBaseId, persistResult.replacedChunkIds());
            }
            luceneIndexManager.indexChunks(knowledgeBaseId, persistResult.chunks());

            markReady(job, document);
        } catch (IOException | RuntimeException e) {
            log.error("ingestion job {} failed at stage {}", jobId, job.getStage(), e);
            markFailed(job, document, e);
        }
    }

    private void transitionToIndexing(IngestionJob job, Document document) {
        transactionTemplate.executeWithoutResult(
                status -> {
                    job.setStatus(IngestionStatus.INDEXING);
                    document.setStatus(IngestionStatus.INDEXING);
                    ingestionJobRepository.save(job);
                    documentRepository.save(document);
                });
    }

    private void markStage(IngestionJob job, String stage) {
        transactionTemplate.executeWithoutResult(
                status -> {
                    job.setStage(stage);
                    ingestionJobRepository.save(job);
                });
    }

    private void markReady(IngestionJob job, Document document) {
        transactionTemplate.executeWithoutResult(
                status -> {
                    job.setStatus(IngestionStatus.READY);
                    job.setStage(null);
                    document.setStatus(IngestionStatus.READY);
                    ingestionJobRepository.save(job);
                    documentRepository.save(document);
                });
    }

    private void markFailed(IngestionJob job, Document document, Exception e) {
        String stageAtFailure = job.getStage();
        transactionTemplate.executeWithoutResult(
                status -> {
                    job.setStatus(IngestionStatus.FAILED);
                    job.setErrorDetail(describeError(e));
                    document.setStatus(IngestionStatus.FAILED);
                    ingestionJobRepository.save(job);
                    documentRepository.save(document);
                });
        ingestionMetrics.recordFailure(stageAtFailure, e.getClass().getSimpleName());
    }

    private static String describeError(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }
}
