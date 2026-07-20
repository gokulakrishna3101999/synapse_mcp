package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.TenantContext;
import com.synapsemcp.document.DocumentStatusSummary;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
import com.synapsemcp.knowledgebase.KnowledgeBaseService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ListKnowledgeBasesMcpToolTest {

    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final ListKnowledgeBasesMcpTool tool =
            new ListKnowledgeBasesMcpTool(knowledgeBaseService);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void delegatesToKnowledgeBaseServiceUsingTheCurrentTenantContext() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        List<KnowledgeBaseResponse> expected =
                List.of(
                        new KnowledgeBaseResponse(
                                UUID.randomUUID(), "kb-1", 1536, DocumentStatusSummary.EMPTY));
        when(knowledgeBaseService.listKnowledgeBases(tenantId)).thenReturn(expected);

        List<KnowledgeBaseResponse> response = tool.listKnowledgeBases();

        assertThat(response).isEqualTo(expected);
    }
}
