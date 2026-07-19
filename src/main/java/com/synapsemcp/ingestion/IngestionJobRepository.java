package com.synapsemcp.ingestion;

import com.synapsemcp.common.IngestionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, UUID> {

    /** {@code document_id} is UNIQUE (Grooming #6b) - exactly one job per document. */
    Optional<IngestionJob> findByDocument_Id(UUID documentId);

    /** rag_plan.md Stage 5d reconciliation: candidates to repair back to {@code READY}. */
    List<IngestionJob> findByStatusAndDocument_KnowledgeBase_Id(
            IngestionStatus status, UUID knowledgeBaseId);

    /** rag_plan.md Stage 5 / Grooming #8: jobs-by-state metric. */
    long countByStatus(IngestionStatus status);
}
