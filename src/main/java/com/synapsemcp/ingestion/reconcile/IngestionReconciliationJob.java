package com.synapsemcp.ingestion.reconcile;

import com.synapsemcp.common.IngestionStatus;
import com.synapsemcp.document.Document;
import com.synapsemcp.document.DocumentRepository;
import com.synapsemcp.ingestion.IngestionJob;
import com.synapsemcp.ingestion.IngestionJobRepository;
import com.synapsemcp.ingestion.index.LuceneIndexManager;
import com.synapsemcp.knowledgebase.KnowledgeBase;
import com.synapsemcp.knowledgebase.KnowledgeBaseRepository;
import com.synapsemcp.rag.chunk.Chunk;
import com.synapsemcp.rag.chunk.ChunkRepository;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * rag_plan.md Stage 5d, Grooming #7: detects and repairs drift between Postgres {@code chunks} and
 * the Lucene index, per knowledge_base, independent of {@code ingestion_jobs.status}. Eventual
 * consistency is acceptable (the plan's own words) - this does not need to be real-time, so a
 * single-threaded, whole-KB-at-a-time sweep on a configurable interval (default hourly) is enough.
 *
 * <p>Also repairs the specific case the plan calls out explicitly: a job left {@code FAILED}
 * because the Postgres commit succeeded but the subsequent Lucene write then threw ({@link
 * com.synapsemcp.ingestion.IngestionPipelineService}'s {@code index} stage, after {@code persist}
 * already committed). Once this sweep has indexed a document's missing chunks, any {@code FAILED}
 * job for that knowledge_base whose document already has committed chunks is flipped back to {@code
 * READY} - the data was always durable and searchable-after-this-repair, so leaving the job
 * permanently {@code FAILED} would misrepresent it.
 */
@Component
public class IngestionReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(IngestionReconciliationJob.class);

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ChunkRepository chunkRepository;
    private final IngestionJobRepository ingestionJobRepository;
    private final DocumentRepository documentRepository;
    private final LuceneIndexManager luceneIndexManager;
    private final TransactionTemplate transactionTemplate;

    IngestionReconciliationJob(
            KnowledgeBaseRepository knowledgeBaseRepository,
            ChunkRepository chunkRepository,
            IngestionJobRepository ingestionJobRepository,
            DocumentRepository documentRepository,
            LuceneIndexManager luceneIndexManager,
            PlatformTransactionManager transactionManager) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.chunkRepository = chunkRepository;
        this.ingestionJobRepository = ingestionJobRepository;
        this.documentRepository = documentRepository;
        this.luceneIndexManager = luceneIndexManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${synapsemcp.reconciliation.interval-ms:3600000}")
    public void reconcile() {
        for (KnowledgeBase knowledgeBase : knowledgeBaseRepository.findAll()) {
            try {
                reconcileKnowledgeBase(knowledgeBase.getId());
            } catch (Exception e) {
                log.error(
                        "ingestion reconciliation failed for knowledge_base {} - will retry next"
                                + " sweep",
                        knowledgeBase.getId(),
                        e);
            }
        }
    }

    private void reconcileKnowledgeBase(UUID knowledgeBaseId) throws IOException {
        List<Chunk> postgresChunks =
                chunkRepository.findByDocument_KnowledgeBase_Id(knowledgeBaseId);
        Set<String> postgresChunkIds =
                postgresChunks.stream().map(c -> c.getId().toString()).collect(Collectors.toSet());
        Set<String> luceneChunkIds = luceneIndexManager.listIndexedChunkIds(knowledgeBaseId);

        List<Chunk> missingFromLucene =
                postgresChunks.stream()
                        .filter(c -> !luceneChunkIds.contains(c.getId().toString()))
                        .toList();
        Set<String> orphanedInLucene = new HashSet<>(luceneChunkIds);
        orphanedInLucene.removeAll(postgresChunkIds);

        if (!missingFromLucene.isEmpty()) {
            luceneIndexManager.indexChunks(knowledgeBaseId, missingFromLucene);
            log.info(
                    "reconciliation: indexed {} chunk(s) missing from the Lucene index for"
                            + " knowledge_base {}",
                    missingFromLucene.size(),
                    knowledgeBaseId);
        }
        if (!orphanedInLucene.isEmpty()) {
            luceneIndexManager.deleteChunks(knowledgeBaseId, orphanedInLucene);
            log.info(
                    "reconciliation: deleted {} orphaned Lucene entr(y/ies) for knowledge_base {}",
                    orphanedInLucene.size(),
                    knowledgeBaseId);
        }

        repairFailedJobsWithDurableChunks(knowledgeBaseId);
    }

    private void repairFailedJobsWithDurableChunks(UUID knowledgeBaseId) {
        List<IngestionJob> failedJobs =
                ingestionJobRepository.findByStatusAndDocument_KnowledgeBase_Id(
                        IngestionStatus.FAILED, knowledgeBaseId);
        for (IngestionJob job : failedJobs) {
            UUID jobId = job.getId();
            UUID documentId = job.getDocumentId();
            if (!chunkRepository.existsByDocument_Id(documentId)) {
                continue;
            }
            // job is a stale snapshot from the query above - a concurrent Grooming #26 retry of
            // this exact job (reset to PENDING, redispatched) could land between that snapshot and
            // this transaction. Re-fetching fresh and re-checking status here, rather than reusing
            // the stale reference, stops this sweep from stomping on that retry's own in-flight
            // state (found live, `plan.md` §9 2026-07-17, alongside the chunk-duplication bug this
            // same retry path had).
            boolean repaired =
                    Boolean.TRUE.equals(
                            transactionTemplate.execute(
                                    status -> {
                                        IngestionJob freshJob =
                                                ingestionJobRepository.findById(jobId).orElse(null);
                                        if (freshJob == null
                                                || freshJob.getStatus() != IngestionStatus.FAILED) {
                                            return false;
                                        }
                                        Document document =
                                                documentRepository
                                                        .findById(documentId)
                                                        .orElseThrow();
                                        freshJob.setStatus(IngestionStatus.READY);
                                        freshJob.setStage(null);
                                        freshJob.setErrorDetail(null);
                                        document.setStatus(IngestionStatus.READY);
                                        ingestionJobRepository.save(freshJob);
                                        documentRepository.save(document);
                                        return true;
                                    }));
            if (repaired) {
                log.info(
                        "reconciliation: repaired job {} (document {}) from FAILED to READY - its"
                                + " chunks were already durable in Postgres, only the Lucene write"
                                + " had failed",
                        jobId,
                        documentId);
            }
        }
    }
}
