package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.TenantContext;
import com.synapsemcp.rag.retrieve.HybridRetrievalService;
import com.synapsemcp.rag.retrieve.SearchMode;
import com.synapsemcp.rag.retrieve.SearchRequest;
import com.synapsemcp.rag.retrieve.SearchResultChunk;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SearchMcpToolTest {

    private final HybridRetrievalService hybridRetrievalService =
            mock(HybridRetrievalService.class);
    private final SearchMcpTool tool = new SearchMcpTool(hybridRetrievalService);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void buildsASearchRequestFromFlatParamsAndDelegates() {
        UUID tenantId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        TenantContext.set(tenantId);
        List<SearchResultChunk> expected =
                List.of(
                        new SearchResultChunk(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                "notes.txt",
                                "content",
                                0.9,
                                null));
        SearchRequest expectedRequest =
                new SearchRequest("what is x?", 5, null, SearchMode.VECTOR, false);
        when(hybridRetrievalService.search(eq(tenantId), eq(kbId), eq(expectedRequest)))
                .thenReturn(expected);

        List<SearchResultChunk> response =
                tool.search(kbId.toString(), "what is x?", 5, "vector", false);

        assertThat(response).isEqualTo(expected);
    }

    @Test
    void throwsAClientSafeErrorForAnInvalidMode() {
        TenantContext.set(UUID.randomUUID());

        assertThatThrownBy(
                        () ->
                                tool.search(
                                        UUID.randomUUID().toString(),
                                        "query",
                                        null,
                                        "not-a-mode",
                                        null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void throwsAClientSafeErrorForAMalformedKnowledgeBaseId() {
        TenantContext.set(UUID.randomUUID());

        assertThatThrownBy(() -> tool.search("not-a-uuid", "query", null, null, null))
                .isInstanceOf(ApiException.class);
    }
}
