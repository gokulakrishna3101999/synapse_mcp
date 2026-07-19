package com.synapsemcp.knowledgebase;

import com.synapsemcp.tenant.Tenant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * {@code embedding_dim} is auto-derived server-side at creation via a live probe of the tenant's
 * embedding model (rag_plan.md Stage 3, Grooming #5b) - never a caller input, and immutable once
 * set.
 *
 * <p>No JPA-level {@code @UniqueConstraint} on {@code (tenant_id, name)} - per-tenant name
 * uniqueness is enforced by a case-insensitive functional unique index ({@code
 * uq_knowledge_bases_tenant_name_ci} on {@code (tenant_id, lower(name))}, {@link
 * com.synapsemcp.config.AnnIndexBootstrapRunner}, Grooming #52) instead, since plain JPA unique
 * constraints can only express exact-value uniqueness and this project deliberately treats
 * case-variant names (e.g. {@code "My KB"} vs {@code "my kb"}) as duplicates (`plan.md` §9,
 * 2026-07-17).
 */
@Entity
@Table(name = "knowledge_bases")
public class KnowledgeBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false)
    private String name;

    @Column(name = "embedding_dim", nullable = false)
    private int embeddingDim;

    protected KnowledgeBase() {}

    KnowledgeBase(Tenant tenant, String name, int embeddingDim) {
        this.tenant = tenant;
        this.name = name;
        this.embeddingDim = embeddingDim;
    }

    /**
     * Public factory replacing the (now package-private) constructor - see {@link
     * com.synapsemcp.ingestion.IngestionJob#create} for why.
     */
    public static KnowledgeBase create(Tenant tenant, String name, int embeddingDim) {
        return new KnowledgeBase(tenant, name, embeddingDim);
    }

    public UUID getId() {
        return id;
    }

    Tenant getTenant() {
        return tenant;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getEmbeddingDim() {
        return embeddingDim;
    }
}
