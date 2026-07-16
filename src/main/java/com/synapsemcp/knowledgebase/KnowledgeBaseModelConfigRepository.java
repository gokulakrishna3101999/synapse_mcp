package com.synapsemcp.knowledgebase;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeBaseModelConfigRepository
        extends JpaRepository<KnowledgeBaseModelConfig, UUID> {

    List<KnowledgeBaseModelConfig> findByKnowledgeBase_Tenant_Id(UUID tenantId);
}
