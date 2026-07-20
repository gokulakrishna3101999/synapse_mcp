package com.synapsemcp.mcp;

import com.synapsemcp.tenant.Tenant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Human MCP account, distinct from a REST tenant's {@code api_keys} row (mcp_plan.md Stage 1,
 * Grooming #11/#12) - authenticates via HTTP Basic against {@code username}/{@code password_hash}
 * (BCrypt, deliberately not the fast SHA-256 {@code ApiKeyHasher} scheme REST uses for
 * machine-generated keys - human-chosen passwords need a slow, salted algorithm). {@code tenant} is
 * nullable: an account starts unlinked, and is permanently linked either at registration time (an
 * {@code api_key} was supplied, Grooming #14) or later via the {@code create_tenant} MCP tool
 * (Grooming #13/#16) - never both, and never re-linkable once set. No uniqueness constraint on
 * {@code tenant_id}: nothing in this plan prevents a second, independently-registered account from
 * later linking to the same tenant via its own valid {@code api_key}.
 */
@Entity
@Table(
        name = "mcp_users",
        indexes = @Index(name = "idx_mcp_users_username", columnList = "username", unique = true))
public class McpUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    private Tenant tenant;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected McpUser() {}

    McpUser(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    Tenant getTenant() {
        return tenant;
    }

    public UUID getTenantId() {
        return tenant == null ? null : tenant.getId();
    }

    /** Package-private: only {@link McpUserRegistrationService} sets this, and only once. */
    void linkTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
