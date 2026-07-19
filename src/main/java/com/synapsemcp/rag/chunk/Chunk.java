package com.synapsemcp.rag.chunk;

import com.synapsemcp.document.Document;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

/**
 * Sparse-dimension-column design (rag_plan.md Stage 0.5 / Stage 6a, Grooming #14/#24): only the
 * column matching the owning knowledge_base's {@code embedding_dim} is ever populated, the other
 * five stay {@code NULL}. Each column keeps its own {@code @Array(length=N)} matching its name
 * (unlike a single flexible column, each of these six is individually fixed-dimension by design) so
 * Postgres rejects a wrong-length vector at the DB level, in addition to the application-layer
 * routing check (Stage 5c). HNSW ANN indexes on these columns are added by {@link
 * com.synapsemcp.config.AnnIndexBootstrapRunner}, since plain JPA/{@code ddl-auto} can't express
 * {@code USING hnsw ... vector_cosine_ops}. {@code tenant_id} is a plain denormalized column, not a
 * FK - intentional (Grooming #16).
 */
@Entity
@Table(
        name = "chunks",
        indexes = {
            @Index(name = "idx_chunks_tenant_doc", columnList = "tenant_id, document_id"),
            @Index(name = "idx_chunks_tenant_kb", columnList = "tenant_id, knowledge_base_id")
        })
public class Chunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /**
     * Denormalized directly onto {@code chunks}, same rationale as {@code tenant_id} (Grooming #16:
     * query-filter performance, no FK, integrity enforced at the application layer) - added
     * specifically so {@code VectorSearchService}'s ANN query can filter by knowledge_base without
     * joining {@code documents}. Confirmed live via {@code EXPLAIN ANALYZE} (`plan.md` §9
     * 2026-07-18) that the join was the structural reason Postgres's planner never considered the
     * HNSW index for the old, joined query - filtering directly on this column is a necessary
     * (though, depending on real-world selectivity, not always sufficient on its own) precondition
     * for the planner to pick the index over a sequential scan.
     */
    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Document document;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = 384)
    @Column(name = "embedding_384")
    private float[] embedding384;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = 512)
    @Column(name = "embedding_512")
    private float[] embedding512;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = 768)
    @Column(name = "embedding_768")
    private float[] embedding768;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = 1024)
    @Column(name = "embedding_1024")
    private float[] embedding1024;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = 1536)
    @Column(name = "embedding_1536")
    private float[] embedding1536;

    /**
     * pgvector's HNSW (and IVFFlat) index has a hard 2000-dimension cap on the plain {@code vector}
     * type - verified empirically, it errors "column cannot have more than 2000 dimensions for hnsw
     * index". {@code halfvec} (half-precision, 16-bit-per-component) raises that cap to 4000, which
     * is what {@link org.hibernate.type.SqlTypes#VECTOR_FLOAT16} maps to on the Postgres dialect -
     * this is pgvector's own documented answer to indexing high-dimensional embeddings, not a
     * workaround. Only this column uses it; the other five stay full-precision {@code vector}.
     */
    @JdbcTypeCode(SqlTypes.VECTOR_FLOAT16)
    @Array(length = 3072)
    @Column(name = "embedding_3072")
    private float[] embedding3072;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new HashMap<>();

    protected Chunk() {}

    Chunk(UUID tenantId, UUID knowledgeBaseId, Document document, int chunkIndex, String content) {
        this.tenantId = tenantId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.document = document;
        this.chunkIndex = chunkIndex;
        this.content = content;
    }

    /**
     * Public factory replacing the (now package-private) constructor - see {@link
     * com.synapsemcp.ingestion.IngestionJob#create} for why.
     */
    public static Chunk create(
            UUID tenantId,
            UUID knowledgeBaseId,
            Document document,
            int chunkIndex,
            String content) {
        return new Chunk(tenantId, knowledgeBaseId, document, chunkIndex, content);
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    Document getDocument() {
        return document;
    }

    public UUID getDocumentId() {
        return document.getId();
    }

    public String getDocumentFilename() {
        return document.getFilename();
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public float[] getEmbedding384() {
        return embedding384 == null ? null : embedding384.clone();
    }

    public void setEmbedding384(float[] embedding384) {
        this.embedding384 = embedding384 == null ? null : embedding384.clone();
    }

    public float[] getEmbedding512() {
        return embedding512 == null ? null : embedding512.clone();
    }

    public void setEmbedding512(float[] embedding512) {
        this.embedding512 = embedding512 == null ? null : embedding512.clone();
    }

    public float[] getEmbedding768() {
        return embedding768 == null ? null : embedding768.clone();
    }

    public void setEmbedding768(float[] embedding768) {
        this.embedding768 = embedding768 == null ? null : embedding768.clone();
    }

    public float[] getEmbedding1024() {
        return embedding1024 == null ? null : embedding1024.clone();
    }

    public void setEmbedding1024(float[] embedding1024) {
        this.embedding1024 = embedding1024 == null ? null : embedding1024.clone();
    }

    public float[] getEmbedding1536() {
        return embedding1536 == null ? null : embedding1536.clone();
    }

    public void setEmbedding1536(float[] embedding1536) {
        this.embedding1536 = embedding1536 == null ? null : embedding1536.clone();
    }

    public float[] getEmbedding3072() {
        return embedding3072 == null ? null : embedding3072.clone();
    }

    public void setEmbedding3072(float[] embedding3072) {
        this.embedding3072 = embedding3072 == null ? null : embedding3072.clone();
    }

    /**
     * Returns a defensive copy - {@code ChunkPersistenceService} used to mutate the map returned
     * here in place (relying on it being the same reference as the internal field); it now builds
     * its own map and calls {@link #setMetadata} explicitly instead, so this copy is safe.
     */
    public Map<String, Object> getMetadata() {
        return metadata == null ? null : new HashMap<>(metadata);
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? null : new HashMap<>(metadata);
    }
}
