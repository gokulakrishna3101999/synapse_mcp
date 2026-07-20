package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.TenantContext;
import com.synapsemcp.document.DocumentStatusSummary;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
import com.synapsemcp.knowledgebase.KnowledgeBaseService;
import com.synapsemcp.knowledgebase.UpdateKnowledgeBaseRequest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UpdateKnowledgeBaseMcpToolTest {

    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final UpdateKnowledgeBaseMcpTool tool =
            new UpdateKnowledgeBaseMcpTool(knowledgeBaseService);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void parsesTheIdAndDelegatesToKnowledgeBaseService() {
        UUID tenantId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        TenantContext.set(tenantId);
        KnowledgeBaseResponse expected =
                new KnowledgeBaseResponse(kbId, "new-name", 1536, DocumentStatusSummary.EMPTY);
        when(knowledgeBaseService.updateKnowledgeBase(
                        eq(tenantId), eq(kbId), eq(new UpdateKnowledgeBaseRequest("new-name"))))
                .thenReturn(expected);

        KnowledgeBaseResponse response = tool.updateKnowledgeBase(kbId.toString(), "new-name");

        assertThat(response).isEqualTo(expected);
    }

    @Test
    void throwsAClientSafeErrorForAMalformedKnowledgeBaseId() {
        TenantContext.set(UUID.randomUUID());

        assertThatThrownBy(() -> tool.updateKnowledgeBase("not-a-uuid", "new-name"))
                .isInstanceOf(ApiException.class);
    }
}
