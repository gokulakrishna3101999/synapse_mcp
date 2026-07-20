package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.TenantContext;
import com.synapsemcp.document.DocumentStatusSummary;
import com.synapsemcp.knowledgebase.CreateKnowledgeBaseRequest;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
import com.synapsemcp.knowledgebase.KnowledgeBaseService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CreateKnowledgeBaseMcpToolTest {

    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final CreateKnowledgeBaseMcpTool tool =
            new CreateKnowledgeBaseMcpTool(knowledgeBaseService);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void delegatesToKnowledgeBaseServiceUsingTheCurrentTenantContext() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        KnowledgeBaseResponse expected =
                new KnowledgeBaseResponse(
                        UUID.randomUUID(), "kb-1", 1536, DocumentStatusSummary.EMPTY);
        when(knowledgeBaseService.createKnowledgeBase(
                        eq(tenantId), eq(new CreateKnowledgeBaseRequest("kb-1"))))
                .thenReturn(expected);

        KnowledgeBaseResponse response = tool.createKnowledgeBase("kb-1");

        assertThat(response).isEqualTo(expected);
    }
}
