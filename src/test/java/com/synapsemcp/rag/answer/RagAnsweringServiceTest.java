package com.synapsemcp.rag.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.chat.ChatModelFactory;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfigRepository;
import com.synapsemcp.rag.RagProperties;
import com.synapsemcp.rag.retrieve.HybridRetrievalService;
import com.synapsemcp.rag.retrieve.SearchRequest;
import com.synapsemcp.rag.retrieve.SearchResultChunk;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class RagAnsweringServiceTest {

    private final HybridRetrievalService hybridRetrievalService =
            mock(HybridRetrievalService.class);
    private final KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository =
            mock(KnowledgeBaseModelConfigRepository.class);
    private final ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
    private final RagProperties ragProperties = new RagProperties();
    private final ChatModel chatModel = mock(ChatModel.class);

    private RagAnsweringService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID knowledgeBaseId = UUID.randomUUID();
    private final KnowledgeBaseModelConfig kbConfig =
            KnowledgeBaseModelConfig.create(
                    null, "openai", "gpt-4o", "openai", "text-embedding-3-small", "creds");

    @BeforeEach
    void setUp() {
        service =
                new RagAnsweringService(
                        hybridRetrievalService,
                        knowledgeBaseModelConfigRepository,
                        chatModelFactory,
                        ragProperties);
        when(knowledgeBaseModelConfigRepository.findByKnowledgeBase_Id(knowledgeBaseId))
                .thenReturn(Optional.of(kbConfig));
        when(chatModelFactory.getChatModelForKnowledgeBase(kbConfig)).thenReturn(chatModel);
        when(chatModelFactory.optionsForKnowledgeBase(kbConfig))
                .thenReturn(ChatOptions.builder().model("gpt-4o").build());
    }

    private static SearchResultChunk chunk(String filename, String content) {
        return new SearchResultChunk(
                UUID.randomUUID(), UUID.randomUUID(), filename, content, 0.9, Map.of());
    }

    private void stubChatCall(String answerText) {
        AssistantMessage message = new AssistantMessage(answerText);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(message))));
    }

    @Test
    void emptyRetrievalShortCircuitsWithoutCallingTheChatModel() {
        when(hybridRetrievalService.search(eq(tenantId), eq(knowledgeBaseId), any()))
                .thenReturn(List.of());

        AskResponse response =
                service.ask(
                        tenantId,
                        knowledgeBaseId,
                        new AskRequest("what is this?", null, null, null, null));

        assertThat(response.answer())
                .isEqualTo("I don't have enough information to answer that question.");
        assertThat(response.citations()).isEmpty();
        verify(chatModel, never()).call(any(Prompt.class));
        verify(chatModelFactory, never()).getChatModelForKnowledgeBase(any());
    }

    @Test
    void nonEmptyRetrievalCallsTheChatModelAndReturnsAnswerWithCitations() {
        SearchResultChunk c1 = chunk("a.txt", "content about apples");
        when(hybridRetrievalService.search(eq(tenantId), eq(knowledgeBaseId), any()))
                .thenReturn(List.of(c1));
        stubChatCall("Apples are great [Source 1].");

        AskResponse response =
                service.ask(
                        tenantId,
                        knowledgeBaseId,
                        new AskRequest("tell me about apples", null, null, null, null));

        assertThat(response.answer()).isEqualTo("Apples are great [Source 1].");
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().get(0).sourceNumber()).isEqualTo(1);
        assertThat(response.citations().get(0).chunkId()).isEqualTo(c1.chunkId());
        assertThat(response.citations().get(0).filename()).isEqualTo("a.txt");
    }

    @Test
    void retrieveUsesTopK20AndPassesThroughModeAndRerank() {
        when(hybridRetrievalService.search(any(), any(), any())).thenReturn(List.of());

        service.ask(
                tenantId,
                knowledgeBaseId,
                new AskRequest(
                        "q", null, null, com.synapsemcp.rag.retrieve.SearchMode.VECTOR, false));

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(hybridRetrievalService).search(eq(tenantId), eq(knowledgeBaseId), captor.capture());
        assertThat(captor.getValue().topK()).isEqualTo(20);
        assertThat(captor.getValue().mode())
                .isEqualTo(com.synapsemcp.rag.retrieve.SearchMode.VECTOR);
        assertThat(captor.getValue().rerank()).isFalse();
    }

    @Test
    void systemPromptNumbersSourcesAndIncludesFilenameAndContent() {
        SearchResultChunk c1 = chunk("doc-one.txt", "first chunk content");
        SearchResultChunk c2 = chunk("doc-two.txt", "second chunk content");
        when(hybridRetrievalService.search(any(), any(), any())).thenReturn(List.of(c1, c2));
        stubChatCall("answer");

        service.ask(tenantId, knowledgeBaseId, new AskRequest("q", null, null, null, null));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        String systemText = captor.getValue().getSystemMessage().getText();
        assertThat(systemText).contains("[Source 1 — doc-one.txt]").contains("first chunk content");
        assertThat(systemText)
                .contains("[Source 2 — doc-two.txt]")
                .contains("second chunk content");
    }

    @Test
    void explicitLanguageIsInstructedInTheSystemPrompt() {
        when(hybridRetrievalService.search(any(), any(), any()))
                .thenReturn(List.of(chunk("a.txt", "x")));
        stubChatCall("answer");

        service.ask(tenantId, knowledgeBaseId, new AskRequest("q", "French", null, null, null));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        assertThat(captor.getValue().getSystemMessage().getText()).contains("Respond in French");
    }

    @Test
    void conversationHistoryIsMappedToUserAndAssistantMessagesBeforeTheQuestion() {
        when(hybridRetrievalService.search(any(), any(), any()))
                .thenReturn(List.of(chunk("a.txt", "x")));
        stubChatCall("answer");
        List<ConversationTurn> history =
                List.of(
                        new ConversationTurn("user", "hi"),
                        new ConversationTurn("assistant", "hello"));

        service.ask(
                tenantId,
                knowledgeBaseId,
                new AskRequest("follow-up question", null, history, null, null));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        List<Message> instructions = captor.getValue().getInstructions();
        // system, user("hi"), assistant("hello"), user("follow-up question")
        assertThat(instructions).hasSize(4);
        assertThat(instructions.get(1).getText()).isEqualTo("hi");
        assertThat(instructions.get(2).getText()).isEqualTo("hello");
        assertThat(instructions.get(3).getText()).isEqualTo("follow-up question");
    }

    @Test
    void contextWindowBudgetingAlwaysKeepsAtLeastTheFirstChunkEvenIfItAloneOverflows() {
        ragProperties.setDefaultContextWindowTokens(1);
        String longContent = "x".repeat(4000);
        SearchResultChunk huge = chunk("huge.txt", longContent);
        SearchResultChunk second = chunk("second.txt", "small");
        when(hybridRetrievalService.search(any(), any(), any())).thenReturn(List.of(huge, second));
        stubChatCall("answer");

        AskResponse response =
                service.ask(tenantId, knowledgeBaseId, new AskRequest("q", null, null, null, null));

        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().get(0).filename()).isEqualTo("huge.txt");
    }

    @Test
    void contextWindowBudgetingExcludesChunksThatWouldExceedTheBudget() {
        ragProperties.setDefaultContextWindowTokens(10);
        SearchResultChunk first = chunk("first.txt", "abcdefgh");
        SearchResultChunk second = chunk("second.txt", "x".repeat(200));
        when(hybridRetrievalService.search(any(), any(), any())).thenReturn(List.of(first, second));
        stubChatCall("answer");

        AskResponse response =
                service.ask(tenantId, knowledgeBaseId, new AskRequest("q", null, null, null, null));

        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().get(0).filename()).isEqualTo("first.txt");
    }

    @Test
    void askStreamEmptyRetrievalShortCircuitsWithNoCitations() {
        when(hybridRetrievalService.search(any(), any(), any())).thenReturn(List.of());

        Flux<ServerSentEvent<String>> stream =
                service.askStream(
                        tenantId, knowledgeBaseId, new AskRequest("q", null, null, null, null));

        StepVerifier.create(stream)
                .assertNext(
                        event ->
                                assertThat(event.data())
                                        .isEqualTo(
                                                "I don't have enough information to answer that question."))
                .assertNext(event -> assertThat(event.event()).isEqualTo("citations"))
                .verifyComplete();
        verify(chatModel, never()).stream(any(Prompt.class));
    }

    @Test
    void askStreamEmitsTokensThenAFinalCitationsEvent() {
        SearchResultChunk c1 = chunk("a.txt", "content");
        when(hybridRetrievalService.search(any(), any(), any())).thenReturn(List.of(c1));
        AssistantMessage token1 = new AssistantMessage("Hello ");
        AssistantMessage token2 = new AssistantMessage("world");
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(List.of(new Generation(token1))),
                                new ChatResponse(List.of(new Generation(token2)))));

        Flux<ServerSentEvent<String>> stream =
                service.askStream(
                        tenantId, knowledgeBaseId, new AskRequest("q", null, null, null, null));

        StepVerifier.create(stream)
                .assertNext(event -> assertThat(event.data()).isEqualTo("Hello "))
                .assertNext(event -> assertThat(event.data()).isEqualTo("world"))
                .assertNext(
                        event -> {
                            assertThat(event.event()).isEqualTo("citations");
                            assertThat(event.data()).contains("\"filename\":\"a.txt\"");
                        })
                .verifyComplete();
    }

    /**
     * Grooming (`plan.md` §9 2026-07-18): found live against real OpenAI - a real provider's
     * streaming {@code Flux} can emit a metadata-only final chunk with a {@code null} {@code
     * getResult()} (no {@code Generation} at all), which every prior test's hand-built {@code Flux}
     * of well-formed {@code ChatResponse}s never exercised.
     */
    @Test
    void askStreamSkipsAChatResponseWithANullResultInsteadOfThrowing() {
        SearchResultChunk c1 = chunk("a.txt", "content");
        when(hybridRetrievalService.search(any(), any(), any())).thenReturn(List.of(c1));
        ChatResponse metadataOnlyChunk = new ChatResponse(List.of());
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        List.of(new Generation(new AssistantMessage("hi")))),
                                metadataOnlyChunk));

        Flux<ServerSentEvent<String>> stream =
                service.askStream(
                        tenantId, knowledgeBaseId, new AskRequest("q", null, null, null, null));

        StepVerifier.create(stream)
                .assertNext(event -> assertThat(event.data()).isEqualTo("hi"))
                .assertNext(event -> assertThat(event.event()).isEqualTo("citations"))
                .verifyComplete();
    }

    @Test
    void askStreamConvertsAMidStreamFailureIntoAnSseErrorEventInsteadOfPropagating() {
        SearchResultChunk c1 = chunk("a.txt", "content");
        when(hybridRetrievalService.search(any(), any(), any())).thenReturn(List.of(c1));
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new RuntimeException("provider connection dropped")));

        Flux<ServerSentEvent<String>> stream =
                service.askStream(
                        tenantId, knowledgeBaseId, new AskRequest("q", null, null, null, null));

        StepVerifier.create(stream)
                .assertNext(event -> assertThat(event.event()).isEqualTo("error"))
                .verifyComplete();
    }
}
