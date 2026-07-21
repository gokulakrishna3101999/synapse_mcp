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
import org.springframework.http.HttpStatus;

class UpdateKnowledgeBaseMcpToolTest {

    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final KnowledgeBaseNameResolver knowledgeBaseNameResolver =
            mock(KnowledgeBaseNameResolver.class);
    private final UpdateKnowledgeBaseMcpTool tool =
            new UpdateKnowledgeBaseMcpTool(knowledgeBaseService, knowledgeBaseNameResolver);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void resolvesTheTargetByNameAndDelegatesToKnowledgeBaseServiceWithTheNewName() {
        UUID tenantId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        TenantContext.set(tenantId);
        when(knowledgeBaseNameResolver.resolve("old-name")).thenReturn(kbId);
        KnowledgeBaseResponse expected =
                new KnowledgeBaseResponse(kbId, "new-name", 1536, DocumentStatusSummary.EMPTY);
        when(knowledgeBaseService.updateKnowledgeBase(
                        eq(tenantId), eq(kbId), eq(new UpdateKnowledgeBaseRequest("new-name"))))
                .thenReturn(expected);

        KnowledgeBaseResponse response = tool.updateKnowledgeBase("old-name", "new-name");

        assertThat(response).isEqualTo(expected);
    }

    @Test
    void propagatesTheResolversOwnErrorForAKnowledgeBaseNameThatDoesNotResolve() {
        TenantContext.set(UUID.randomUUID());
        when(knowledgeBaseNameResolver.resolve("does-not-exist"))
                .thenThrow(
                        new ApiException(
                                HttpStatus.NOT_FOUND, "Not Found", "knowledge base not found"));

        assertThatThrownBy(() -> tool.updateKnowledgeBase("does-not-exist", "new-name"))
                .isInstanceOf(ApiException.class)
                .hasMessage("knowledge base not found");
    }
}
