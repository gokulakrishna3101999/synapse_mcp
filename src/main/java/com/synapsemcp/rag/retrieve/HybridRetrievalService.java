package com.synapsemcp.rag.retrieve;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.ingestion.embed.ChunkEmbeddingService;
import com.synapsemcp.ingestion.index.LuceneIndexManager;
import com.synapsemcp.knowledgebase.KnowledgeBase;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfigRepository;
import com.synapsemcp.knowledgebase.KnowledgeBaseRepository;
import com.synapsemcp.rag.chunk.Chunk;
import com.synapsemcp.rag.chunk.ChunkRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * rag_plan.md Stage 6a: {@code POST /api/v1/knowledgebase/{id}/search} - hybrid retrieval (BM25
 * keyword + ANN vector, merged by RRF), or either method alone via {@code mode}.
 *
 * <p>The query embedding call (vector/hybrid mode only) and the Lucene keyword search both run as
 * plain, non-transactional calls - deliberately, mirroring {@code
 * KnowledgeBaseService.probeEmbeddingDimension}'s own reasoning (`plan.md` §9 2026-07-17): a
 * slow/unresponsive embedding provider must never hold a pooled DB connection idle. The only DB
 * read in this class ({@link ChunkRepository#findByIdInFetchDocument}) happens last, after both
 * ranked lists (and any RRF merge) are already computed, and uses a {@code JOIN FETCH} so no
 * explicit transaction is needed to safely read each result's {@code document.filename}.
 */
@Service
public class HybridRetrievalService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository;
    private final ChunkRepository chunkRepository;
    private final ChunkEmbeddingService chunkEmbeddingService;
    private final VectorSearchService vectorSearchService;
    private final LuceneIndexManager luceneIndexManager;
    private final LlmRerankerService llmRerankerService;

    HybridRetrievalService(
            KnowledgeBaseRepository knowledgeBaseRepository,
            KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository,
            ChunkRepository chunkRepository,
            ChunkEmbeddingService chunkEmbeddingService,
            VectorSearchService vectorSearchService,
            LuceneIndexManager luceneIndexManager,
            LlmRerankerService llmRerankerService) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeBaseModelConfigRepository = knowledgeBaseModelConfigRepository;
        this.chunkRepository = chunkRepository;
        this.chunkEmbeddingService = chunkEmbeddingService;
        this.vectorSearchService = vectorSearchService;
        this.luceneIndexManager = luceneIndexManager;
        this.llmRerankerService = llmRerankerService;
    }

    public List<SearchResultChunk> search(
            UUID tenantId, UUID knowledgeBaseId, SearchRequest request) {
        KnowledgeBase knowledgeBase = requireOwnedKnowledgeBase(tenantId, knowledgeBaseId);
        SearchMode mode = request.mode();
        int topK = request.topK();

        List<ScoredChunkId> vectorRanked =
                mode == SearchMode.KEYWORD
                        ? List.of()
                        : vectorSearch(tenantId, knowledgeBase, request.query(), topK);
        List<ScoredChunkId> keywordRanked =
                mode == SearchMode.VECTOR
                        ? List.of()
                        : keywordSearch(knowledgeBaseId, request.query(), topK);

        List<ScoredChunkId> ranked =
                switch (mode) {
                    case VECTOR -> vectorRanked;
                    case KEYWORD -> keywordRanked;
                    case HYBRID ->
                            ReciprocalRankFusion.merge(
                                    List.of(vectorRanked, keywordRanked), request.rrfK());
                };

        List<ScoredChunkId> truncated = ranked.size() > topK ? ranked.subList(0, topK) : ranked;
        List<SearchResultChunk> hydrated = hydrate(truncated);

        if (request.rerank() && hydrated.size() > 1) {
            KnowledgeBaseModelConfig kbConfig = requireModelConfig(knowledgeBaseId);
            return llmRerankerService.rerank(kbConfig, request.query(), hydrated);
        }
        return hydrated;
    }

    private List<ScoredChunkId> vectorSearch(
            UUID tenantId, KnowledgeBase knowledgeBase, String queryText, int topK) {
        KnowledgeBaseModelConfig kbConfig = requireModelConfig(knowledgeBase.getId());
        float[] queryVector = chunkEmbeddingService.embed(kbConfig, List.of(queryText)).get(0);
        return vectorSearchService.search(
                tenantId,
                knowledgeBase.getId(),
                knowledgeBase.getEmbeddingDim(),
                queryVector,
                topK);
    }

    private List<ScoredChunkId> keywordSearch(UUID knowledgeBaseId, String queryText, int topK) {
        try {
            return luceneIndexManager.search(knowledgeBaseId, queryText, topK);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "keyword search failed for knowledge_base " + knowledgeBaseId, e);
        }
    }

    private List<SearchResultChunk> hydrate(List<ScoredChunkId> ranked) {
        if (ranked.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = ranked.stream().map(ScoredChunkId::chunkId).toList();
        Map<UUID, Chunk> chunksById = new HashMap<>();
        for (Chunk chunk : chunkRepository.findByIdInFetchDocument(ids)) {
            chunksById.put(chunk.getId(), chunk);
        }

        List<SearchResultChunk> results = new ArrayList<>(ranked.size());
        for (ScoredChunkId scored : ranked) {
            Chunk chunk = chunksById.get(scored.chunkId());
            if (chunk == null) {
                // Stale index entry (Lucene ahead of a since-deleted chunk) - the reconciliation
                // cron's job to repair, not this request's; simply skip it here.
                continue;
            }
            results.add(
                    new SearchResultChunk(
                            chunk.getId(),
                            chunk.getDocumentId(),
                            chunk.getDocumentFilename(),
                            chunk.getContent(),
                            scored.score(),
                            chunk.getMetadata()));
        }
        return results;
    }

    /**
     * A knowledge_base belonging to a different tenant is treated identically to a nonexistent id -
     * both return {@code 404} (Grooming #44, same contract {@code KnowledgeBaseService} uses).
     */
    private KnowledgeBase requireOwnedKnowledgeBase(UUID tenantId, UUID knowledgeBaseId) {
        return knowledgeBaseRepository
                .findByIdAndTenant_Id(knowledgeBaseId, tenantId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        HttpStatus.NOT_FOUND,
                                        "Not Found",
                                        "knowledge base not found"));
    }

    /**
     * Every knowledge_base gets this snapshot at creation time (Grooming #23) - a missing row here
     * is an invariant violation, not a client-facing condition, so it surfaces as a plain {@code
     * 500} via the generic exception handler rather than a translated {@link ApiException}.
     */
    private KnowledgeBaseModelConfig requireModelConfig(UUID knowledgeBaseId) {
        return knowledgeBaseModelConfigRepository
                .findByKnowledgeBase_Id(knowledgeBaseId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "knowledge_base "
                                                + knowledgeBaseId
                                                + " has no model config snapshot - should be"
                                                + " impossible, created together with the"
                                                + " knowledge_base"));
    }
}
