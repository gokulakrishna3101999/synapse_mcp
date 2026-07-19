package com.synapsemcp.knowledgebase;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeBaseModelConfigRepository
        extends JpaRepository<KnowledgeBaseModelConfig, UUID> {

    List<KnowledgeBaseModelConfig> findByKnowledgeBase_Tenant_Id(UUID tenantId);

    /** rag_plan.md Stage 5c: the locked model snapshot ingestion reads from (Grooming #23). */
    Optional<KnowledgeBaseModelConfig> findByKnowledgeBase_Id(UUID knowledgeBaseId);
}
