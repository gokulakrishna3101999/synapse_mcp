package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.TenantContext;
import com.synapsemcp.knowledgebase.KnowledgeBaseService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DeleteKnowledgeBaseMcpToolTest {

    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final DeleteKnowledgeBaseMcpTool tool =
            new DeleteKnowledgeBaseMcpTool(knowledgeBaseService);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void parsesTheIdDelegatesDeletionAndReturnsAConfirmation() {
        UUID tenantId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        TenantContext.set(tenantId);

        DeleteKnowledgeBaseResult result = tool.deleteKnowledgeBase(kbId.toString());

        assertThat(result).isEqualTo(new DeleteKnowledgeBaseResult(kbId, true));
        verify(knowledgeBaseService).deleteKnowledgeBase(tenantId, kbId);
    }

    @Test
    void throwsAClientSafeErrorForAMalformedKnowledgeBaseId() {
        TenantContext.set(UUID.randomUUID());

        assertThatThrownBy(() -> tool.deleteKnowledgeBase("not-a-uuid"))
                .isInstanceOf(ApiException.class);
    }
}
