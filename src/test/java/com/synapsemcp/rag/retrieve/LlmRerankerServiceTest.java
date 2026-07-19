package com.synapsemcp.rag.retrieve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.chat.ChatModelFactory;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;

class LlmRerankerServiceTest {

    private final ChatModelFactory chatModelFactory = mock(ChatModelFactory.class);
    private final ChatModel chatModel = mock(ChatModel.class);
    private final LlmRerankerService service = new LlmRerankerService(chatModelFactory);
    private final KnowledgeBaseModelConfig kbConfig =
            KnowledgeBaseModelConfig.create(
                    null, "openai", "gpt-4o", "openai", "text-embedding-3-small", "creds");

    @BeforeEach
    void setUp() {
        when(chatModelFactory.getChatModelForKnowledgeBase(kbConfig)).thenReturn(chatModel);
        when(chatModelFactory.optionsForKnowledgeBase(kbConfig))
                .thenReturn(ChatOptions.builder().model("gpt-4o").build());
    }

    private static SearchResultChunk candidate(String content, double score) {
        return new SearchResultChunk(
                UUID.randomUUID(), UUID.randomUUID(), "f.txt", content, score, Map.of());
    }

    private void stubChatResponse(String text) {
        AssistantMessage message = new AssistantMessage(text);
        Generation generation = new Generation(message);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new ChatResponse(List.of(generation)));
    }

    @Test
    void singleCandidateSkipsTheRerankerEntirely() {
        List<SearchResultChunk> candidates = List.of(candidate("only one", 0.5));

        List<SearchResultChunk> result = service.rerank(kbConfig, "q", candidates);

        assertThat(result).isEqualTo(candidates);
        org.mockito.Mockito.verifyNoInteractions(chatModelFactory);
    }

    @Test
    void emptyCandidateListSkipsTheRerankerEntirely() {
        List<SearchResultChunk> result = service.rerank(kbConfig, "q", List.of());

        assertThat(result).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(chatModelFactory);
    }

    @Test
    void reordersCandidatesByTheRerankerScoreInTheExactRequestedFormat() {
        SearchResultChunk first = candidate("first candidate, was ranked first by retrieval", 0.9);
        SearchResultChunk second = candidate("second candidate, actually more relevant", 0.5);
        stubChatResponse("1: 20\n2: 95");

        List<SearchResultChunk> result = service.rerank(kbConfig, "q", List.of(first, second));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).chunkId()).isEqualTo(second.chunkId());
        assertThat(result.get(0).score()).isEqualTo(95.0);
        assertThat(result.get(1).chunkId()).isEqualTo(first.chunkId());
        assertThat(result.get(1).score()).isEqualTo(20.0);
    }

    @Test
    void toleratesAPassagePrefixAndPeriodSeparatorVariant() {
        SearchResultChunk first = candidate("a", 0.9);
        SearchResultChunk second = candidate("b", 0.5);
        stubChatResponse("Passage 1. 10\nPassage 2. 88");

        List<SearchResultChunk> result = service.rerank(kbConfig, "q", List.of(first, second));

        assertThat(result.get(0).chunkId()).isEqualTo(second.chunkId());
    }

    @Test
    void keepsOriginalScoreForACandidateWhoseLineIsMissingFromTheResponse() {
        SearchResultChunk first = candidate("a", 0.42);
        SearchResultChunk second = candidate("b", 0.11);
        // Only passage 2 scored - passage 1's original retrieval score should be preserved, not
        // lost.
        stubChatResponse("2: 99");

        List<SearchResultChunk> result = service.rerank(kbConfig, "q", List.of(first, second));

        assertThat(result.get(0).chunkId()).isEqualTo(second.chunkId());
        assertThat(result.get(1).chunkId()).isEqualTo(first.chunkId());
        assertThat(result.get(1).score()).isEqualTo(0.42);
    }

    @Test
    void fallsBackToOriginalOrderWhenTheResponseHasNoParseableScoreLines() {
        List<SearchResultChunk> candidates = List.of(candidate("a", 0.9), candidate("b", 0.5));
        stubChatResponse("I cannot help with that request.");

        List<SearchResultChunk> result = service.rerank(kbConfig, "q", candidates);

        assertThat(result).isEqualTo(candidates);
    }

    @Test
    void fallsBackToOriginalOrderWhenTheProviderCallThrows() {
        List<SearchResultChunk> candidates = List.of(candidate("a", 0.9), candidate("b", 0.5));
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("provider unreachable"));

        List<SearchResultChunk> result = service.rerank(kbConfig, "q", candidates);

        assertThat(result).isEqualTo(candidates);
    }
}
