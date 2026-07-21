package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.TenantContext;
import com.synapsemcp.knowledgebase.KnowledgeBaseService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DeleteKnowledgeBaseMcpToolTest {

    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final KnowledgeBaseNameResolver knowledgeBaseNameResolver =
            mock(KnowledgeBaseNameResolver.class);
    private final DeleteKnowledgeBaseMcpTool tool =
            new DeleteKnowledgeBaseMcpTool(knowledgeBaseService, knowledgeBaseNameResolver);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void resolvesTheNameDelegatesDeletionAndReturnsAConfirmation() {
        UUID tenantId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        TenantContext.set(tenantId);
        when(knowledgeBaseNameResolver.resolve("my-kb")).thenReturn(kbId);

        DeleteKnowledgeBaseResult result = tool.deleteKnowledgeBase("my-kb");

        assertThat(result).isEqualTo(new DeleteKnowledgeBaseResult(kbId, true));
        verify(knowledgeBaseService).deleteKnowledgeBase(tenantId, kbId);
    }

    @Test
    void propagatesTheResolversOwnErrorForAKnowledgeBaseNameThatDoesNotResolve() {
        TenantContext.set(UUID.randomUUID());
        when(knowledgeBaseNameResolver.resolve("does-not-exist"))
                .thenThrow(
                        new ApiException(
                                HttpStatus.NOT_FOUND, "Not Found", "knowledge base not found"));

        assertThatThrownBy(() -> tool.deleteKnowledgeBase("does-not-exist"))
                .isInstanceOf(ApiException.class)
                .hasMessage("knowledge base not found");
    }
}
