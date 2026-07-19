package com.synapsemcp.tenant;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    /**
     * Pessimistic row lock, held for the duration of the caller's transaction - used by {@code
     * KnowledgeBaseService.createKnowledgeBase} to serialize the 10-knowledge-base-per-tenant count
     * check against concurrent creates for the same tenant (rag_plan.md Stage 3, `plan.md` §9
     * 2026-07-17). A plain count-then-insert has no unique constraint to catch a losing race the
     * way {@code model_configs.tenant_id} does, so this lock is the only backstop.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Tenant t WHERE t.id = :id")
    Optional<Tenant> lockById(UUID id);
}
