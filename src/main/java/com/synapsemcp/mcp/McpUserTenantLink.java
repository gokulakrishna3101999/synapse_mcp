package com.synapsemcp.mcp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Records every distinct tenant a given {@code mcp_users} account has ever attached to via {@code
 * create_tenant} or {@code switch_tenant} (user-requested, 2026-07-21: "only 5 tenants per mcp_user
 * including create and switch tenants"). Deliberately append-only and separate from {@code
 * mcp_users.tenant_id} (the account's currently active tenant, which {@code switch_tenant} freely
 * repoints back and forth) - this table is the permanent history used only to (a) recognize a
 * "revisit" to an already-touched tenant, which never consumes a new slot, and (b) count distinct
 * tenants ever touched, capped at {@code McpTenantSwitchingService.MAX_TENANTS_PER_MCP_USER}. Raw
 * UUID columns rather than {@code @ManyToOne} relationships since this table is only ever queried
 * by id pair, never navigated as an object graph.
 */
@Entity
@Table(
        name = "mcp_user_tenant_links",
        uniqueConstraints = @UniqueConstraint(columnNames = {"mcp_user_id", "tenant_id"}))
public class McpUserTenantLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "mcp_user_id", nullable = false)
    private UUID mcpUserId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @CreationTimestamp
    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    protected McpUserTenantLink() {}

    McpUserTenantLink(UUID mcpUserId, UUID tenantId) {
        this.mcpUserId = mcpUserId;
        this.tenantId = tenantId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMcpUserId() {
        return mcpUserId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }
}
