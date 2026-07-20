package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.TenantContext;
import com.synapsemcp.rag.evaluate.EvaluateQuery;
import com.synapsemcp.rag.evaluate.EvaluateRequest;
import com.synapsemcp.rag.evaluate.EvaluateResponse;
import com.synapsemcp.rag.evaluate.EvaluationService;
import com.synapsemcp.rag.retrieve.SearchMode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EvaluateMcpToolTest {

    private final EvaluationService evaluationService = mock(EvaluationService.class);
    private final EvaluateMcpTool tool = new EvaluateMcpTool(evaluationService);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void buildsAnEvaluateRequestFromFlatParamsAndTheQueriesListAndDelegates() {
        UUID tenantId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        TenantContext.set(tenantId);
        List<EvaluateQuery> queries =
                List.of(new EvaluateQuery("what is x?", List.of(UUID.randomUUID())));
        EvaluateResponse expected = new EvaluateResponse(List.of(), 1.0, 1.0, 1.0);
        EvaluateRequest expectedRequest =
                new EvaluateRequest(queries, 5, SearchMode.KEYWORD, false);
        when(evaluationService.evaluate(eq(tenantId), eq(kbId), eq(expectedRequest)))
                .thenReturn(expected);

        EvaluateResponse response = tool.evaluate(kbId.toString(), queries, 5, "keyword", false);

        assertThat(response).isEqualTo(expected);
    }

    @Test
    void throwsAClientSafeErrorForAnInvalidMode() {
        TenantContext.set(UUID.randomUUID());
        List<EvaluateQuery> queries = List.of(new EvaluateQuery("q", List.of()));

        assertThatThrownBy(
                        () ->
                                tool.evaluate(
                                        UUID.randomUUID().toString(),
                                        queries,
                                        null,
                                        "not-a-mode",
                                        null))
                .isInstanceOf(ApiException.class);
    }
}
