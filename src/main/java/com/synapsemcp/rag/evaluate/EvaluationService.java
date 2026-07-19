package com.synapsemcp.rag.evaluate;

import com.synapsemcp.rag.retrieve.HybridRetrievalService;
import com.synapsemcp.rag.retrieve.SearchRequest;
import com.synapsemcp.rag.retrieve.SearchResultChunk;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * rag_plan.md Stage 6c: "This is a testing/development tool - not part of the runtime answer flow."
 * Runs each golden query through {@link HybridRetrievalService#search} - the exact same retrieval
 * path {@code /search} and {@code /ask} both use, so a golden-query batch measures the genuine,
 * currently-configured retrieval behavior (mode, rerank on/off, topK), not a separate
 * evaluation-only code path that could silently drift from what real queries actually do.
 */
@Service
public class EvaluationService {

    private final HybridRetrievalService hybridRetrievalService;

    public EvaluationService(HybridRetrievalService hybridRetrievalService) {
        this.hybridRetrievalService = hybridRetrievalService;
    }

    public EvaluateResponse evaluate(UUID tenantId, UUID knowledgeBaseId, EvaluateRequest request) {
        List<EvaluateQueryResult> perQuery = new ArrayList<>(request.queries().size());
        for (EvaluateQuery query : request.queries()) {
            SearchRequest searchRequest =
                    new SearchRequest(
                            query.query(), request.topK(), null, request.mode(), request.rerank());
            List<SearchResultChunk> results =
                    hybridRetrievalService.search(tenantId, knowledgeBaseId, searchRequest);
            perQuery.add(score(query, results));
        }

        return new EvaluateResponse(
                perQuery,
                average(perQuery, EvaluateQueryResult::precisionAtK),
                average(perQuery, EvaluateQueryResult::recallAtK),
                average(perQuery, EvaluateQueryResult::reciprocalRank));
    }

    /**
     * Precision@K/recall@K use {@code min(K, retrieved.size())} as the precision denominator per
     * standard IR convention when fewer than {@code K} results come back - a knowledge_base with
     * only 3 chunks can never achieve a "perfect" precision@10 score by that shortfall alone.
     * {@code expectedChunkIds} empty is treated as vacuous recall (1.0, nothing to find) rather
     * than undefined (0/0) - a deliberate, documented convention (see {@link EvaluateQueryResult}).
     */
    private static EvaluateQueryResult score(EvaluateQuery query, List<SearchResultChunk> results) {
        Set<UUID> expected = new HashSet<>(query.expectedChunkIds());
        List<UUID> retrievedIds = results.stream().map(SearchResultChunk::chunkId).toList();

        long relevantRetrieved = retrievedIds.stream().filter(expected::contains).count();
        double precisionAtK =
                retrievedIds.isEmpty() ? 0.0 : (double) relevantRetrieved / retrievedIds.size();
        double recallAtK = expected.isEmpty() ? 1.0 : (double) relevantRetrieved / expected.size();

        double reciprocalRank = 0.0;
        for (int i = 0; i < retrievedIds.size(); i++) {
            if (expected.contains(retrievedIds.get(i))) {
                reciprocalRank = 1.0 / (i + 1);
                break;
            }
        }

        return new EvaluateQueryResult(query.query(), precisionAtK, recallAtK, reciprocalRank);
    }

    private static double average(
            List<EvaluateQueryResult> results,
            java.util.function.ToDoubleFunction<EvaluateQueryResult> metric) {
        return results.stream().mapToDouble(metric).average().orElse(0.0);
    }
}
