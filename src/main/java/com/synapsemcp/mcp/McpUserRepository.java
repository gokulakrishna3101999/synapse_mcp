package com.synapsemcp.mcp;

import com.synapsemcp.tenant.Tenant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface McpUserRepository extends JpaRepository<McpUser, UUID> {

    Optional<McpUser> findByUsername(String username);

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
}
