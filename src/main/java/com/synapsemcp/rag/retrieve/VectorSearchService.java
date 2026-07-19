package com.synapsemcp.rag.retrieve;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * rag_plan.md Stage 6a: pgvector cosine ANN search, routed to the sparse column matching the
 * knowledge_base's {@code embedding_dim} (Grooming #14/#24, same routing this codebase already uses
 * for writes in {@code ChunkPersistenceService}). The column name is resolved through a fixed
 * switch over the 6 supported dimensions - never built by concatenating the caller-supplied
 * dimension directly into SQL - both as defense-in-depth (this int is always internally-sourced
 * from {@code KnowledgeBase.getEmbeddingDim()}, validated at KB-creation time, never caller input)
 * and to fail loudly on an unsupported dimension rather than build invalid SQL.
 *
 * <p>The query vector has no native JDBC "vector" parameter type available through plain JPA/
 * Hibernate native queries, so it's bound as pgvector's own documented text input format (a
 * bracketed, comma-separated string, e.g. {@code "[0.1,0.2,0.3]"}) and cast on the Postgres side
 * (verified live via {@code psql}: {@code CAST('[1,2,3]' AS vector) <=> CAST('[1,2,4]' AS vector)}
 * works identically whether the string is a literal or a bound parameter).
 *
 * <p>{@code embedding_3072} is {@code halfvec}, not {@code vector} (pgvector's HNSW index caps at
 * 2000 dimensions for the plain type - see {@code Chunk.getEmbedding3072()}'s own Javadoc) - the
 * cast target type is chosen alongside the column name.
 *
 * <p><b>Filters directly on {@code chunks.knowledge_base_id}/{@code chunks.tenant_id} - no join to
 * {@code documents}</b> (`plan.md` §9 2026-07-18, found live via {@code EXPLAIN ANALYZE} against a
 * realistic multi-tenant dataset, not assumed): the original version of this query joined {@code
 * documents} to reach {@code knowledge_base_id}, and Postgres's planner never once considered the
 * HNSW index for that shape at any selectivity tested - always a sequential scan + sort, directly
 * contradicting rag_plan.md's own "ANN-optimized vector search... no full sequential scans"
 * acceptance criterion. Filtering on a column that lives directly on {@code chunks} (added
 * specifically for this fix, {@link com.synapsemcp.rag.chunk.Chunk#getKnowledgeBaseId()}) is a
 * necessary precondition for the planner to ever pick the index - confirmed live it is *not*
 * sufficient on its own at every selectivity (the planner is still cost-based and may reasonably
 * prefer a sequential scan when a knowledge_base is a large fraction of the physical table), but
 * removing the join removes the structural blocker that made the index scan altogether inadmissible
 * regardless of cost.
 *
 * <p>{@code hnsw.iterative_scan} is set to {@code relaxed_order} on every pooled connection (
 * {@code spring.datasource.hikari.connection-init-sql}, every profile) specifically so that
 * *if/when* the planner does choose the HNSW index path for a filtered query like this one, the
 * scan is guaranteed to keep searching until it finds {@code topK} genuinely matching rows rather
 * than silently returning fewer (or zero) results after only inspecting a small candidate window
 * near the query vector - confirmed live via {@code EXPLAIN ANALYZE} that a plain (non-iterative)
 * filtered HNSW scan can return 0 rows despite real matches existing elsewhere in the vector space.
 */
@Service
public class VectorSearchService {

    private final EntityManager entityManager;

    VectorSearchService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * @return chunk ids ranked by cosine similarity (highest first), never more than {@code topK}.
     */
    @SuppressWarnings("unchecked")
    public List<ScoredChunkId> search(
            UUID tenantId, UUID knowledgeBaseId, int embeddingDim, float[] queryVector, int topK) {
        String column = columnNameFor(embeddingDim);
        String castType = embeddingDim == 3072 ? "halfvec" : "vector";
        String sql =
                "SELECT c.id, 1.0 - (c."
                        + column
                        + " <=> CAST(:queryVector AS "
                        + castType
                        + ")) AS score "
                        + "FROM chunks c "
                        + "WHERE c.knowledge_base_id = :kbId AND c.tenant_id = :tenantId "
                        + "AND c."
                        + column
                        + " IS NOT NULL "
                        + "ORDER BY c."
                        + column
                        + " <=> CAST(:queryVector AS "
                        + castType
                        + ") "
                        + "LIMIT :topK";

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("queryVector", formatVector(queryVector));
        query.setParameter("kbId", knowledgeBaseId);
        query.setParameter("tenantId", tenantId);
        query.setParameter("topK", topK);

        List<Object[]> rows = query.getResultList();
        List<ScoredChunkId> results = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            UUID chunkId = (UUID) row[0];
            double score = ((Number) row[1]).doubleValue();
            results.add(new ScoredChunkId(chunkId, score));
        }
        return results;
    }

    private static String columnNameFor(int embeddingDim) {
        return switch (embeddingDim) {
            case 384 -> "embedding_384";
            case 512 -> "embedding_512";
            case 768 -> "embedding_768";
            case 1024 -> "embedding_1024";
            case 1536 -> "embedding_1536";
            case 3072 -> "embedding_3072";
            default ->
                    throw new IllegalStateException(
                            "unsupported embedding dimension: " + embeddingDim);
        };
    }

    private static String formatVector(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
