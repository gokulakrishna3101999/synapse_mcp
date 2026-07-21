package com.synapsemcp.mcp;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Wraps an authenticated {@code mcp_users} row (mcp_plan.md Stage 1). Carries {@code mcpUserId},
 * the (possibly {@code null}) {@code tenantId}, and (possibly {@code null}) {@code
 * activeKnowledgeBaseId} straight off {@link
 * org.springframework.security.core.context.SecurityContextHolder} so {@code McpToolAccessAspect}/
 * {@code McpToolInputs} need no extra DB query per tool call - Basic Auth is stateless here (no
 * session, Grooming #1/#17), so {@code McpUserDetailsService#loadUserByUsername} re-runs fresh on
 * every request and none of this is ever stale.
 */
public class McpUserPrincipal implements UserDetails {

    private static final long serialVersionUID = 1L;

    private final UUID mcpUserId;
    private final String username;
    private final String passwordHash;
    private final UUID tenantId;
    private final UUID activeKnowledgeBaseId;

    McpUserPrincipal(
            UUID mcpUserId,
            String username,
            String passwordHash,
            UUID tenantId,
            UUID activeKnowledgeBaseId) {
        this.mcpUserId = mcpUserId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.tenantId = tenantId;
        this.activeKnowledgeBaseId = activeKnowledgeBaseId;
    }

    public UUID getMcpUserId() {
        return mcpUserId;
    }

    /** {@code null} means this account is not yet linked to a tenant (Grooming #13). */
    public UUID getTenantId() {
        return tenantId;
    }

    /**
     * {@code null} means this account has no active knowledge base set yet - see {@code
     * switch_knowledge_base} (user-requested, 2026-07-22).
     */
    public UUID getActiveKnowledgeBaseId() {
        return activeKnowledgeBaseId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }
}
