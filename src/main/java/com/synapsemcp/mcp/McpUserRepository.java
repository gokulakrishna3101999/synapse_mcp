package com.synapsemcp.mcp;

import com.synapsemcp.knowledgebase.KnowledgeBase;
import com.synapsemcp.tenant.Tenant;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface McpUserRepository extends JpaRepository<McpUser, UUID> {

    Optional<McpUser> findByUsername(String username);

    /**
     * Pessimistic row lock, held for the duration of the caller's transaction - used by {@code
     * McpTenantSwitchingService.switchTenant} to serialize the 5-tenants-per-account count check
     * (mcp_plan.md Grooming #26) against concurrent {@code switch_tenant} calls for the same
     * account, the same race-safety pattern {@code TenantRepository.lockById}/{@code
     * KnowledgeBaseRepository.lockById} already use for their own per-parent count checks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM McpUser m WHERE m.id = :id")
    Optional<McpUser> lockById(UUID id);

    /**
     * Atomic conditional link (mcp_plan.md Grooming #16): affects a row only if the account is
     * still unlinked at the moment this statement runs. Used by the {@code create_tenant} MCP tool,
     * inside the same {@code @Transactional} boundary as the tenant/API-key creation it's linking
     * to - a {@code 0} return means a concurrent call already won the race, and the caller must
     * roll the whole transaction back rather than leave an orphaned tenant behind.
     */
    @Modifying
    @Query("UPDATE McpUser m SET m.tenant = :tenant WHERE m.id = :mcpUserId AND m.tenant IS NULL")
    int linkTenantIfUnlinked(@Param("mcpUserId") UUID mcpUserId, @Param("tenant") Tenant tenant);

    /**
     * User-requested (2026-07-21): unconditionally repoints an already-registered account at a
     * different tenant, used by the {@code switch_tenant} MCP tool - deliberately unconditional,
     * unlike {@link #linkTenantIfUnlinked}'s atomic "only if still null" guard, since switching has
     * no analogous race to protect against (there is no "should only ever happen once" invariant
     * here; the caller has already proven ownership of the target tenant's API key by the time this
     * runs, in {@code McpTenantSwitchingService}). Always affects exactly one row, since {@code
     * mcpUserId} is always the id of the currently-authenticated principal.
     */
    @Modifying
    @Query("UPDATE McpUser m SET m.tenant = :tenant WHERE m.id = :mcpUserId")
    int switchTenant(@Param("mcpUserId") UUID mcpUserId, @Param("tenant") Tenant tenant);

    /**
     * User-requested (2026-07-22): unconditionally repoints an already-registered account at a
     * different knowledge base, used by the {@code switch_knowledge_base} MCP tool - the caller has
     * already had this knowledge base's ownership validated (by name, scoped to the caller's own
     * tenant) in {@code McpActiveKnowledgeBaseSwitchingService} by the time this runs, so no
     * further guard is needed here, mirroring {@link #switchTenant}'s own unconditional shape.
     */
    @Modifying
    @Query("UPDATE McpUser m SET m.activeKnowledgeBase = :knowledgeBase WHERE m.id = :mcpUserId")
    int switchActiveKnowledgeBase(
            @Param("mcpUserId") UUID mcpUserId,
            @Param("knowledgeBase") KnowledgeBase knowledgeBase);

    /**
     * User-requested (2026-07-22): clears the account's active knowledge base - called by {@code
     * switch_tenant} (a knowledge base from a since-abandoned tenant is never meaningful as
     * "active" for a newly-switched-to one). Deleting the active knowledge base itself already
     * clears this column automatically at the database level ({@code ON DELETE SET NULL} on {@code
     * McpUser.activeKnowledgeBase}) - this method is only for the tenant-switch case, which has no
     * comparable database-level trigger to rely on.
     */
    @Modifying
    @Query("UPDATE McpUser m SET m.activeKnowledgeBase = NULL WHERE m.id = :mcpUserId")
    int clearActiveKnowledgeBase(@Param("mcpUserId") UUID mcpUserId);
}
