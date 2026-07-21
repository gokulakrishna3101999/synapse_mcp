package com.synapsemcp.mcp;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpUserTenantLinkRepository extends JpaRepository<McpUserTenantLink, UUID> {

    boolean existsByMcpUserIdAndTenantId(UUID mcpUserId, UUID tenantId);

    long countByMcpUserId(UUID mcpUserId);
}
