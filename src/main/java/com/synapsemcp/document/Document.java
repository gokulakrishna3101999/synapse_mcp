package com.synapsemcp.document;

import com.synapsemcp.common.IngestionStatus;
import com.synapsemcp.knowledgebase.KnowledgeBase;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * {@code tenant_id} is a plain denormalized column, not a FK - intentional (rag_plan.md Stage 0.5,
 * Grooming #16): integrity is enforced at the application layer for query-filter performance.
 *
 * <p>{@code knowledge_base_id} cascades on delete (added Stage 3, `plan.md` §9 2026-07-17 - the
 * original Stage 0.5 schema had no cascade here, only {@code chunks.document_id}/{@code
 * ingestion_jobs.document_id} cascaded FROM documents). Since those two already cascade, this
 * single addition makes a knowledge_base delete cascade the whole tree (KB -&gt; documents -&gt;
 * chunks + jobs) at the DB level in one statement, satisfying Stage 3's "delete cascades to
 * documents and chunks" requirement without any application-layer explicit deletion.
 */
@Entity
@Table(
        name = "documents",
        indexes = {
            @Index(name = "idx_documents_tenant_kb", columnList = "tenant_id, knowledge_base_id"),
            @Index(
                    name = "idx_documents_tenant_kb_content_hash",
                    columnList = "tenant_id, knowledge_base_id, content_hash",
                    unique = true)
        })
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "knowledge_base_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private KnowledgeBase knowledgeBase;

    @Column(nullable = false)
    private String filename;

    @Column(name = "file_type", nullable = false)
    private String fileType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestionStatus status;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "extractor_name")
    private String extractorName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Document() {}

    Document(
            UUID tenantId,
            KnowledgeBase knowledgeBase,
            String filename,
            String fileType,
            IngestionStatus status,
            String contentHash) {
        this.tenantId = tenantId;
        this.knowledgeBase = knowledgeBase;
        this.filename = filename;
        this.fileType = fileType;
        this.status = status;
        this.contentHash = contentHash;
    }

    /**
     * Public factory replacing the (now package-private) constructor - see {@link
     * com.synapsemcp.ingestion.IngestionJob#create} for why.
     */
    public static Document create(
            UUID tenantId,
            KnowledgeBase knowledgeBase,
            String filename,
            String fileType,
            IngestionStatus status,
            String contentHash) {
        return new Document(tenantId, knowledgeBase, filename, fileType, status, contentHash);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    KnowledgeBase getKnowledgeBase() {
        return knowledgeBase;
    }

    public UUID getKnowledgeBaseId() {
        return knowledgeBase.getId();
    }

    public String getFilename() {
        return filename;
    }

    public String getFileType() {
        return fileType;
    }

    public IngestionStatus getStatus() {
        return status;
    }

    public void setStatus(IngestionStatus status) {
        this.status = status;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getExtractorName() {
        return extractorName;
    }

    public void setExtractorName(String extractorName) {
        this.extractorName = extractorName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
