package com.synapsemcp.rag.chunk;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ChunkRepository extends JpaRepository<Chunk, UUID> {

    List<Chunk> findByDocument_Id(UUID documentId);

    /**
     * rag_plan.md Stage 6a: hydrates search-ranked chunk ids into full results. {@code JOIN FETCH}
     * loads {@code document} eagerly in the same query, so the returned entities are safe to read
     * {@code document.getFilename()} from with no active transaction/session needed at the call
     * site - the standard fix for the exact lazy-association pitfall Grooming #66 (Stage 5) found
     * live, applied here proactively instead of after a failure.
     */
    @Query("SELECT c FROM Chunk c JOIN FETCH c.document WHERE c.id IN :ids")
    List<Chunk> findByIdInFetchDocument(List<UUID> ids);

    /**
     * rag_plan.md Stage 5d reconciliation: every chunk currently committed for a knowledge_base.
     */
    List<Chunk> findByDocument_KnowledgeBase_Id(UUID knowledgeBaseId);

    /**
     * rag_plan.md Stage 5d reconciliation: whether a document's chunks are durably committed to
     * Postgres - true for a document whose {@code ingestion_jobs} row is {@code FAILED} only
     * because the later Lucene write threw, chunk persistence itself having already succeeded.
     */
    boolean existsByDocument_Id(UUID documentId);
}
