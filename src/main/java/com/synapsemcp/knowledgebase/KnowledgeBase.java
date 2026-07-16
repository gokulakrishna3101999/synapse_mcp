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
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

/**
 * {@code embedding_dim} is auto-derived server-side at creation via a live probe of the tenant's
 * embedding model (rag_plan.md Stage 3, Grooming #5b) - never a caller input, and immutable once
 * set.
 */
@Entity
@Table(
        name = "knowledge_bases",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_knowledge_bases_tenant_name",
                        columnNames = {"tenant_id", "name"}))
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

    public KnowledgeBase(Tenant tenant, String name, int embeddingDim) {
        this.tenant = tenant;
        this.name = name;
        this.embeddingDim = embeddingDim;
    }

    public UUID getId() {
        return id;
    }

    public Tenant getTenant() {
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
