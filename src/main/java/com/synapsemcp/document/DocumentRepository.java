package com.synapsemcp.document;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    /**
     * The idempotency lookup (rag_plan.md Stage 4, Grooming #6b/#26) - a {@code Document} already
     * exists for this exact {@code (tenantId, knowledgeBaseId, contentHash)} triple, so the
     * caller's upload short-circuits against its existing status rather than creating a duplicate.
     */
    Optional<Document> findByTenantIdAndKnowledgeBase_IdAndContentHash(
            UUID tenantId, UUID knowledgeBaseId, String contentHash);
}
