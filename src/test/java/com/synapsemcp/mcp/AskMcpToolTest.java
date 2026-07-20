package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.TenantContext;
import com.synapsemcp.rag.answer.AskRequest;
import com.synapsemcp.rag.answer.AskResponse;
import com.synapsemcp.rag.answer.ConversationTurn;
import com.synapsemcp.rag.answer.RagAnsweringService;
import com.synapsemcp.rag.retrieve.SearchMode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AskMcpToolTest {

    private final RagAnsweringService ragAnsweringService = mock(RagAnsweringService.class);
    private final AskMcpTool tool = new AskMcpTool(ragAnsweringService);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void buildsAnAskRequestFromFlatParamsIncludingHistoryAndDelegates() {
        UUID tenantId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        TenantContext.set(tenantId);
        List<ConversationTurn> history = List.of(new ConversationTurn("user", "hi"));
        AskResponse expected = new AskResponse("the answer", List.of());
        AskRequest expectedRequest =
                new AskRequest("what is x?", "en", history, SearchMode.HYBRID, true);
        when(ragAnsweringService.ask(eq(tenantId), eq(kbId), eq(expectedRequest)))
                .thenReturn(expected);

        AskResponse response = tool.ask(kbId.toString(), "what is x?", "en", history, null, true);

        assertThat(response).isEqualTo(expected);
    }

    @Test
    void callsTheNonStreamingAskMethodOnlyAndNothingElse() {
        UUID tenantId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        TenantContext.set(tenantId);
        when(ragAnsweringService.ask(eq(tenantId), eq(kbId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AskResponse("answer", List.of()));

        tool.ask(kbId.toString(), "question", null, null, null, null);

        org.mockito.Mockito.verify(ragAnsweringService)
                .ask(eq(tenantId), eq(kbId), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoMoreInteractions(ragAnsweringService);
    }
}
