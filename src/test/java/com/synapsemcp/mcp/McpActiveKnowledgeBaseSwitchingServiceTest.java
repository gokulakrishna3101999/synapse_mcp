package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.document.DocumentRepository;
import com.synapsemcp.knowledgebase.KnowledgeBase;
import com.synapsemcp.knowledgebase.KnowledgeBaseRepository;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

class McpActiveKnowledgeBaseSwitchingServiceTest {

    private final KnowledgeBaseRepository knowledgeBaseRepository =
            mock(KnowledgeBaseRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final McpUserRepository mcpUserRepository = mock(McpUserRepository.class);
    private final McpActiveKnowledgeBaseSwitchingService switchingService =
            new McpActiveKnowledgeBaseSwitchingService(
                    knowledgeBaseRepository, documentRepository, mcpUserRepository);

    @Test
    void switchesToTheKnowledgeBaseWhenTheNameGenuinelyBelongsToTheCallersTenant() {
        UUID mcpUserId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        KnowledgeBase knowledgeBase = mock(KnowledgeBase.class);
        when(knowledgeBase.getId()).thenReturn(kbId);
        when(knowledgeBase.getName()).thenReturn("My Knowledge Base");
        when(knowledgeBase.getEmbeddingDim()).thenReturn(1536);
        when(knowledgeBaseRepository.findByNameIgnoreCaseAndTenant_Id(
                        "My Knowledge Base", tenantId))
                .thenReturn(Optional.of(knowledgeBase));
        when(documentRepository.countByKnowledgeBaseIdGroupedByStatus(kbId)).thenReturn(List.of());

        KnowledgeBaseResponse result =
                switchingService.switchKnowledgeBase(mcpUserId, tenantId, "My Knowledge Base");

        assertThat(result.id()).isEqualTo(kbId);
        assertThat(result.name()).isEqualTo("My Knowledge Base");
        verify(mcpUserRepository).switchActiveKnowledgeBase(mcpUserId, knowledgeBase);
    }

    @Test
    void rejectsANameThatDoesNotBelongToTheCallersTenant() {
        UUID mcpUserId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(knowledgeBaseRepository.findByNameIgnoreCaseAndTenant_Id("Someone Elses KB", tenantId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                switchingService.switchKnowledgeBase(
                                        mcpUserId, tenantId, "Someone Elses KB"))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.NOT_FOUND));
        verify(mcpUserRepository, never()).switchActiveKnowledgeBase(any(), any());
    }

    /**
     * "Final" architect-level validation round (mcp_plan.md Grooming #29): found live, not guessed,
     * that a concurrent {@code delete_knowledge_base} for the exact knowledge base being switched
     * to - landing between the name lookup above and this method's own persist step - violates the
     * {@code active_knowledge_base_id} foreign key and threw a raw {@code
     * DataIntegrityViolationException} straight through to the MCP client (constraint name, table,
     * column, and the offending id all included) before this catch existed. REST gets this
     * translated for free by {@code ApiExceptionHandler}'s global handler; MCP tool calls never go
     * through that layer at all.
     */
    @Test
    void translatesAConcurrentDeleteRaceIntoTheSameCleanNotFoundError() {
        UUID mcpUserId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        KnowledgeBase knowledgeBase = mock(KnowledgeBase.class);
        when(knowledgeBaseRepository.findByNameIgnoreCaseAndTenant_Id("race-kb", tenantId))
                .thenReturn(Optional.of(knowledgeBase));
        when(mcpUserRepository.switchActiveKnowledgeBase(mcpUserId, knowledgeBase))
                .thenThrow(
                        new DataIntegrityViolationException(
                                "insert or update on table \"mcp_users\" violates foreign key"
                                        + " constraint \"fka7p5d8363cfmvn1qasi99e439\""));

        assertThatThrownBy(
                        () -> switchingService.switchKnowledgeBase(mcpUserId, tenantId, "race-kb"))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessage("knowledge base not found");
        verify(documentRepository, never()).countByKnowledgeBaseIdGroupedByStatus(any());
    }
}
