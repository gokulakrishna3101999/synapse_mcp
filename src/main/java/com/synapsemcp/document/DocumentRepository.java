package com.synapsemcp.document;

import com.synapsemcp.common.IngestionStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    /**
     * The idempotency lookup (rag_plan.md Stage 4, Grooming #6b/#26) - a {@code Document} already
     * exists for this exact {@code (tenantId, knowledgeBaseId, contentHash)} triple, so the
     * caller's upload short-circuits against its existing status rather than creating a duplicate.
     */
    Optional<Document> findByTenantIdAndKnowledgeBase_IdAndContentHash(
            UUID tenantId, UUID knowledgeBaseId, String contentHash);

    /**
     * One row per {@code (knowledge_base_id, status)} pair across every knowledge_base the tenant
     * owns - mcp_plan.md Stage 2 {@code get_tenant}/{@code list_knowledge_bases} document-count
     * rollup, a single {@code GROUP BY} query rather than the {@code IngestionMetrics} precedent's
     * per-status-per-KB loop (up to 40 queries for a 10-knowledge_base tenant).
     */
    @Query(
            "SELECT d.knowledgeBase.id AS knowledgeBaseId, d.status AS status, COUNT(d) AS count "
                    + "FROM Document d WHERE d.tenantId = :tenantId "
                    + "GROUP BY d.knowledgeBase.id, d.status")
    List<KnowledgeBaseStatusCount> countByTenantIdGroupedByKnowledgeBaseAndStatus(
            @Param("tenantId") UUID tenantId);

    /**
     * Single-knowledge_base shape of the same rollup - used by {@code create}/{@code update} paths
     * that only need one KB's summary refreshed, not the whole tenant's.
     */
    @Query(
            "SELECT d.status AS status, COUNT(d) AS count FROM Document d "
                    + "WHERE d.knowledgeBase.id = :knowledgeBaseId GROUP BY d.status")
    List<StatusCount> countByKnowledgeBaseIdGroupedByStatus(
            @Param("knowledgeBaseId") UUID knowledgeBaseId);

    interface StatusCount {
        IngestionStatus getStatus();

        long getCount();
    }

    interface KnowledgeBaseStatusCount extends StatusCount {
        UUID getKnowledgeBaseId();
    }
}
