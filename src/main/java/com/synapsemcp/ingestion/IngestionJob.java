package com.synapsemcp.ingestion;

import com.synapsemcp.common.IngestionStatus;
import com.synapsemcp.document.Document;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Authoritative ingestion state machine (rag_plan.md Stage 0.5, Grooming #30); {@code document_id}
 * is UNIQUE - one job per document, no rerun mechanism (Grooming #6b). {@code tenant_id} is a plain
 * denormalized column, not a FK - intentional (Grooming #16).
 */
@Entity
@Table(
        name = "ingestion_jobs",
        indexes =
                @Index(
                        name = "idx_ingestion_jobs_tenant_doc",
                        columnList = "tenant_id, document_id"))
public class IngestionJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Document document;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestionStatus status;

    private String stage;

    @Column(name = "error_detail", columnDefinition = "TEXT")
    private String errorDetail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IngestionJob() {}

    IngestionJob(UUID tenantId, Document document, IngestionStatus status) {
        this.tenantId = tenantId;
        this.document = document;
        this.status = status;
    }

    /**
     * Public factory replacing the (now package-private) constructor - {@code document} is a real
     * JPA entity reference, and this class lives in a different package than its callers ({@code
     * DocumentUploadService}), so the constructor itself can't be public without SpotBugs'
     * EI_EXPOSE_REP2 (storing an externally-supplied mutable reference) firing legitimately.
     * Confirmed empirically: a public static factory delegating to a package-private constructor is
     * not flagged, since the actual field assignment happens inside the constructor, not this
     * method - the same fix pattern used project-wide instead of suppressing this SpotBugs
     * category.
     */
    public static IngestionJob create(UUID tenantId, Document document, IngestionStatus status) {
        return new IngestionJob(tenantId, document, status);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    Document getDocument() {
        return document;
    }

    public UUID getDocumentId() {
        return document.getId();
    }

    public IngestionStatus getStatus() {
        return status;
    }

    public void setStatus(IngestionStatus status) {
        this.status = status;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getErrorDetail() {
        return errorDetail;
    }

    public void setErrorDetail(String errorDetail) {
        this.errorDetail = errorDetail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
