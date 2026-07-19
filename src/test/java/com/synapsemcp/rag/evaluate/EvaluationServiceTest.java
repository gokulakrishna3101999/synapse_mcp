package com.synapsemcp.rag.evaluate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.rag.retrieve.HybridRetrievalService;
import com.synapsemcp.rag.retrieve.SearchResultChunk;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationServiceTest {

    private final HybridRetrievalService hybridRetrievalService =
            mock(HybridRetrievalService.class);
    private final EvaluationService service = new EvaluationService(hybridRetrievalService);
    private final UUID tenantId = UUID.randomUUID();
    private final UUID knowledgeBaseId = UUID.randomUUID();

    private static SearchResultChunk result(UUID chunkId) {
        return new SearchResultChunk(chunkId, UUID.randomUUID(), "f.txt", "content", 0.9, Map.of());
    }

    @Test
    void computesPrecisionRecallAndReciprocalRankForAPerfectFirstHit() {
        UUID relevant = UUID.randomUUID();
        UUID irrelevant = UUID.randomUUID();
        when(hybridRetrievalService.search(eq(tenantId), eq(knowledgeBaseId), any()))
                .thenReturn(List.of(result(relevant), result(irrelevant)));
        EvaluateRequest request =
                new EvaluateRequest(
                        List.of(new EvaluateQuery("q", List.of(relevant))), null, null, null);

        EvaluateResponse response = service.evaluate(tenantId, knowledgeBaseId, request);

        EvaluateQueryResult queryResult = response.perQuery().get(0);
        assertThat(queryResult.precisionAtK()).isCloseTo(0.5, within(1e-9));
        assertThat(queryResult.recallAtK()).isCloseTo(1.0, within(1e-9));
        assertThat(queryResult.reciprocalRank()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void reciprocalRankReflectsTheRankOfTheFirstRelevantHit() {
        UUID relevant = UUID.randomUUID();
        when(hybridRetrievalService.search(any(), any(), any()))
                .thenReturn(
                        List.of(
                                result(UUID.randomUUID()),
                                result(UUID.randomUUID()),
                                result(relevant)));
        EvaluateRequest request =
                new EvaluateRequest(
                        List.of(new EvaluateQuery("q", List.of(relevant))), null, null, null);

        EvaluateResponse response = service.evaluate(tenantId, knowledgeBaseId, request);

        assertThat(response.perQuery().get(0).reciprocalRank()).isCloseTo(1.0 / 3, within(1e-9));
    }

    @Test
    void reciprocalRankIsZeroWhenNothingRelevantIsRetrieved() {
        when(hybridRetrievalService.search(any(), any(), any()))
                .thenReturn(List.of(result(UUID.randomUUID())));
        EvaluateRequest request =
                new EvaluateRequest(
                        List.of(new EvaluateQuery("q", List.of(UUID.randomUUID()))),
                        null,
                        null,
                        null);

        EvaluateResponse response = service.evaluate(tenantId, knowledgeBaseId, request);

        EvaluateQueryResult queryResult = response.perQuery().get(0);
        assertThat(queryResult.reciprocalRank()).isZero();
        assertThat(queryResult.precisionAtK()).isZero();
        assertThat(queryResult.recallAtK()).isZero();
    }

    @Test
    void emptyExpectedChunkIdsIsTreatedAsVacuousRecall() {
        when(hybridRetrievalService.search(any(), any(), any()))
                .thenReturn(List.of(result(UUID.randomUUID())));
        EvaluateRequest request =
                new EvaluateRequest(List.of(new EvaluateQuery("q", List.of())), null, null, null);

        EvaluateResponse response = service.evaluate(tenantId, knowledgeBaseId, request);

        assertThat(response.perQuery().get(0).recallAtK()).isEqualTo(1.0);
    }

    @Test
    void noResultsRetrievedGivesZeroPrecisionAndRecall() {
        when(hybridRetrievalService.search(any(), any(), any())).thenReturn(List.of());
        EvaluateRequest request =
                new EvaluateRequest(
                        List.of(new EvaluateQuery("q", List.of(UUID.randomUUID()))),
                        null,
                        null,
                        null);

        EvaluateResponse response = service.evaluate(tenantId, knowledgeBaseId, request);

        EvaluateQueryResult queryResult = response.perQuery().get(0);
        assertThat(queryResult.precisionAtK()).isZero();
        assertThat(queryResult.recallAtK()).isZero();
        assertThat(queryResult.reciprocalRank()).isZero();
    }

    @Test
    void meanMetricsAverageAcrossAllQueriesInTheBatch() {
        UUID relevantA = UUID.randomUUID();
        UUID relevantB = UUID.randomUUID();
        when(hybridRetrievalService.search(eq(tenantId), eq(knowledgeBaseId), any()))
                .thenReturn(List.of(result(relevantA)))
                .thenReturn(List.of(result(UUID.randomUUID())));
        EvaluateRequest request =
                new EvaluateRequest(
                        List.of(
                                new EvaluateQuery("q1", List.of(relevantA)),
                                new EvaluateQuery("q2", List.of(relevantB))),
                        null,
                        null,
                        null);

        EvaluateResponse response = service.evaluate(tenantId, knowledgeBaseId, request);

        assertThat(response.perQuery()).hasSize(2);
        assertThat(response.meanPrecisionAtK()).isCloseTo(0.5, within(1e-9));
        assertThat(response.meanRecallAtK()).isCloseTo(0.5, within(1e-9));
        assertThat(response.meanReciprocalRank()).isCloseTo(0.5, within(1e-9));
    }
}
