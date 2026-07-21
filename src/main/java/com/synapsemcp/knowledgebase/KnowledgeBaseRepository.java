package com.synapsemcp.knowledgebase;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, UUID> {

    long countByTenant_Id(UUID tenantId);

    List<KnowledgeBase> findAllByTenant_IdOrderByNameAsc(UUID tenantId);

    /**
     * Own-tenant-only lookup used by get/update/delete - a knowledge_base belonging to a different
     * tenant is treated identically to a nonexistent id (both come back empty), so a caller can
     * never tell the difference from the response alone (rag_plan.md Stage 3, `plan.md` §9
     * 2026-07-17).
     */
    Optional<KnowledgeBase> findByIdAndTenant_Id(UUID id, UUID tenantId);

    /**
     * User-requested (2026-07-22): name-based lookup for the {@code switch_knowledge_base} MCP tool
     * - case-insensitive to match {@code uq_knowledge_bases_tenant_name_ci}'s own per-tenant
     * uniqueness guarantee (on {@code (tenant_id, lower(name))}), so exactly zero or one row can
     * ever match. Same own-tenant-only shape as {@link #findByIdAndTenant_Id}: a name belonging to
     * a different tenant's knowledge base is indistinguishable from a nonexistent one.
     */
    Optional<KnowledgeBase> findByNameIgnoreCaseAndTenant_Id(String name, UUID tenantId);

    /**
     * Serializes concurrent document uploads to the same knowledge_base (Stage 4, `plan.md` §9
     * 2026-07-17) - same {@code SELECT ... FOR UPDATE} pattern {@code TenantRepository.lockById}
     * already uses for the 10-KB-per-tenant limit. Found live: without this, concurrent uploads of
     * identical new content raced past the idempotency check-then-insert (N-1 losers got a raw
     * {@code 409} with no job info instead of the promised idempotent short-circuit), and
     * concurrent retries of the same {@code FAILED} job all dispatched onto the async pipeline
     * simultaneously.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT k FROM KnowledgeBase k WHERE k.id = :id")
    Optional<KnowledgeBase> lockById(UUID id);
}
