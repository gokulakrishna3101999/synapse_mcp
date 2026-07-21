package com.synapsemcp.mcp;

import com.synapsemcp.knowledgebase.KnowledgeBase;
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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * Human MCP account, distinct from a REST tenant's {@code api_keys} row (mcp_plan.md Stage 1,
 * Grooming #11/#12) - authenticates via HTTP Basic against {@code username}/{@code password_hash}
 * (BCrypt, deliberately not the fast SHA-256 {@code ApiKeyHasher} scheme REST uses for
 * machine-generated keys - human-chosen passwords need a slow, salted algorithm). {@code tenant} is
 * nullable: an account starts unlinked, and links either at registration time (an {@code api_key}
 * was supplied, Grooming #14) or later via the {@code create_tenant} MCP tool (Grooming #13/#16).
 * Unlike the original design, this link is no longer permanent: the {@code switch_tenant} MCP tool
 * (user-requested, 2026-07-21) can repoint an already-linked account at a *different*, already-
 * existing tenant, provided the caller proves ownership of that tenant's own API key - see {@link
 * McpUserRepository#switchTenant}. No uniqueness constraint on {@code tenant_id}: nothing in this
 * plan prevents a second, independently-registered account from linking to the same tenant via its
 * own valid {@code api_key}, or the same account from switching among several tenants it holds keys
 * for over time.
 *
 * <p>{@code activeKnowledgeBase} (user-requested, 2026-07-22): the account's currently-selected
 * knowledge base, set via the {@code switch_knowledge_base} MCP tool - every other knowledge-base
 * tool (ask/search/ingest/evaluate/update_knowledge_base/delete_knowledge_base) falls back to it
 * whenever a caller omits an explicit {@code knowledgeBaseId}. Cleared automatically at the
 * database level ({@code ON DELETE SET NULL}) if that knowledge base is later deleted, and
 * explicitly cleared by {@code switch_tenant} too (a knowledge base from a since-abandoned tenant
 * is never meaningful as "active" for a newly-switched-to one) - see {@link
 * McpUserRepository#switchActiveKnowledgeBase}/{@link McpUserRepository#clearActiveKnowledgeBase}.
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "active_knowledge_base_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private KnowledgeBase activeKnowledgeBase;

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

    public UUID getActiveKnowledgeBaseId() {
        return activeKnowledgeBase == null ? null : activeKnowledgeBase.getId();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
