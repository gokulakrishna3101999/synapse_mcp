package com.synapsemcp.rag.answer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapsemcp.chat.ChatModelFactory;
import com.synapsemcp.common.RateLimitKind;
import com.synapsemcp.common.RateLimited;
import com.synapsemcp.ingestion.chunk.TokenEstimator;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfigRepository;
import com.synapsemcp.rag.RagProperties;
import com.synapsemcp.rag.retrieve.HybridRetrievalService;
import com.synapsemcp.rag.retrieve.SearchRequest;
import com.synapsemcp.rag.retrieve.SearchResultChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * rag_plan.md Stage 6b: {@code POST /api/v1/knowledgebase/{id}/ask} - hybrid retrieve → rerank
 * (both via {@link HybridRetrievalService#search}, which also serves as the tenant/knowledge_base
 * authorization check - no duplicate check here) → context-window budgeting → prompt assembly →
 * chat model → grounded answer with citations.
 *
 * <p>Multi-language support is delegated entirely to the chat model itself via the system prompt
 * instruction ("respond in the same language as the question" or an explicit {@code language}
 * override) - no separate Java-side language-detection library was added; a real LLM is already
 * reliably good at this, and detecting language correctly enough to trust in Java would need its
 * own model/dependency (a decision deliberately avoided rather than guessed at, same spirit as the
 * reranker model choice).
 */
@Service
public class RagAnsweringService {

    private static final Logger log = LoggerFactory.getLogger(RagAnsweringService.class);
    private static final int RETRIEVAL_TOP_K = 20;
    private static final String NO_INFORMATION_ANSWER =
            "I don't have enough information to answer that question.";

    private final HybridRetrievalService hybridRetrievalService;
    private final KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository;
    private final ChatModelFactory chatModelFactory;
    private final RagProperties ragProperties;

    /**
     * A plain, unconfigured instance rather than an injected Spring bean: this Spring Boot
     * 4/Framework 7 version defaults its own JSON auto-configuration to Jackson 3 ({@code
     * tools.jackson.databind.ObjectMapper}), so no Spring-managed bean of the classic Jackson 2
     * {@code com.fasterxml.jackson.databind.ObjectMapper} type exists in this application context
     * at all - confirmed live (`plan.md` §9 2026-07-18): injecting one here threw {@code
     * NoSuchBeanDefinitionException} at context startup. Jackson 2 is still present transitively
     * (pulled in by another dependency's own JSON-schema tooling), and a bare, default-configured
     * instance is all this class needs - only for serializing the small, plain {@link Citation}
     * record list into one SSE event's data, nothing that depends on any of this app's
     * globally-configured Jackson customizations.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    RagAnsweringService(
            HybridRetrievalService hybridRetrievalService,
            KnowledgeBaseModelConfigRepository knowledgeBaseModelConfigRepository,
            ChatModelFactory chatModelFactory,
            RagProperties ragProperties) {
        this.hybridRetrievalService = hybridRetrievalService;
        this.knowledgeBaseModelConfigRepository = knowledgeBaseModelConfigRepository;
        this.chatModelFactory = chatModelFactory;
        this.ragProperties = ragProperties;
    }

    @RateLimited(RateLimitKind.CHAT)
    public AskResponse ask(UUID tenantId, UUID knowledgeBaseId, AskRequest request) {
        List<SearchResultChunk> retrieved = retrieve(tenantId, knowledgeBaseId, request);
        if (retrieved.isEmpty()) {
            return new AskResponse(NO_INFORMATION_ANSWER, List.of());
        }

        KnowledgeBaseModelConfig kbConfig = requireModelConfig(knowledgeBaseId);
        List<SearchResultChunk> budgeted =
                budgetToContextWindow(retrieved, kbConfig.getChatModel());
        List<Citation> citations = toCitations(budgeted);
        List<Message> messages = buildMessages(budgeted, request);

        ChatModel chatModel = chatModelFactory.getChatModelForKnowledgeBase(kbConfig);
        ChatOptions options = chatModelFactory.optionsForKnowledgeBase(kbConfig);
        ChatResponse response = chatModel.call(new Prompt(messages, options));
        String answer = response.getResult().getOutput().getText();

        return new AskResponse(answer, citations);
    }

    /**
     * SSE variant: token deltas as unnamed ("message") events, followed by exactly one final {@code
     * citations} event once the answer completes (rag_plan.md: "In streaming mode, citations are
     * sent as a final SSE event after the answer completes").
     */
    @RateLimited(RateLimitKind.CHAT)
    public Flux<ServerSentEvent<String>> askStream(
            UUID tenantId, UUID knowledgeBaseId, AskRequest request) {
        List<SearchResultChunk> retrieved = retrieve(tenantId, knowledgeBaseId, request);
        if (retrieved.isEmpty()) {
            return Flux.just(
                    ServerSentEvent.builder(NO_INFORMATION_ANSWER).build(),
                    citationsEvent(List.of()));
        }

        KnowledgeBaseModelConfig kbConfig = requireModelConfig(knowledgeBaseId);
        List<SearchResultChunk> budgeted =
                budgetToContextWindow(retrieved, kbConfig.getChatModel());
        List<Citation> citations = toCitations(budgeted);
        List<Message> messages = buildMessages(budgeted, request);

        ChatModel chatModel = chatModelFactory.getChatModelForKnowledgeBase(kbConfig);
        ChatOptions options = chatModelFactory.optionsForKnowledgeBase(kbConfig);

        Flux<ServerSentEvent<String>> tokens =
                chatModel.stream(new Prompt(messages, options))
                        // A real provider's streaming Flux can emit a metadata-only final chunk
                        // with no Generation at all (confirmed live against real OpenAI, `plan.md`
                        // §9 2026-07-18 - every prior test used a mocked Flux of well-formed
                        // ChatResponses and never exercised this) - naively calling
                        // .getResult().getOutput() on it threw a live NullPointerException that
                        // then cascaded into a second failure (the JSON error handler can't write
                        // to an already-committed text/event-stream response), silently truncating
                        // the stream before the citations event ever arrived.
                        .filter(chatResponse -> chatResponse.getResult() != null)
                        .map(chatResponse -> chatResponse.getResult().getOutput().getText())
                        .filter(text -> text != null && !text.isEmpty())
                        .map(text -> ServerSentEvent.builder(text).build());

        return Flux.concat(tokens, Flux.just(citationsEvent(citations)))
                .onErrorResume(
                        e -> {
                            // Once the response's Content-Type commits to text/event-stream (the
                            // first emitted element), Spring MVC's normal exception handling can no
                            // longer help - confirmed live: ApiExceptionHandler's own attempt to
                            // write a JSON ProblemDetail into an already-committed SSE response
                            // itself threw HttpMessageNotWritableException, masking the real error
                            // behind a second, unrelated one. An SSE-native error event is the only
                            // way a mid-stream failure can be reported to the client at all.
                            log.error("ask stream failed mid-flight", e);
                            return Flux.just(
                                    ServerSentEvent.<String>builder()
                                            .event("error")
                                            .data("the answer stream failed unexpectedly")
                                            .build());
                        });
    }

    private List<SearchResultChunk> retrieve(
            UUID tenantId, UUID knowledgeBaseId, AskRequest request) {
        SearchRequest searchRequest =
                new SearchRequest(
                        request.question(),
                        RETRIEVAL_TOP_K,
                        null,
                        request.mode(),
                        request.rerank());
        return hybridRetrievalService.search(tenantId, knowledgeBaseId, searchRequest);
    }

    /**
     * Greedily includes {@code ranked} chunks (already rerank-ordered, if reranking was enabled)
     * until the next one would exceed the resolved chat model's context-window token budget - but
     * always keeps at least the first chunk even if it alone overflows (rag_plan.md: "always
     * keeping at least one").
     */
    private List<SearchResultChunk> budgetToContextWindow(
            List<SearchResultChunk> ranked, String chatModelName) {
        int budget = ragProperties.contextWindowTokensFor(chatModelName);
        List<SearchResultChunk> included = new ArrayList<>();
        int usedTokens = 0;
        for (SearchResultChunk chunk : ranked) {
            int tokens = TokenEstimator.estimateTokens(chunk.content());
            if (!included.isEmpty() && usedTokens + tokens > budget) {
                break;
            }
            included.add(chunk);
            usedTokens += tokens;
        }
        return included;
    }

    private List<Message> buildMessages(List<SearchResultChunk> budgeted, AskRequest request) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt(budgeted, request.language())));
        for (ConversationTurn turn : request.history()) {
            messages.add(
                    turn.isAssistant()
                            ? new AssistantMessage(turn.content())
                            : new UserMessage(turn.content()));
        }
        messages.add(new UserMessage(request.question()));
        return messages;
    }

    private static String buildSystemPrompt(List<SearchResultChunk> budgeted, String language) {
        StringBuilder sb = new StringBuilder();
        sb.append(
                "You are a helpful assistant answering questions using only the numbered sources"
                        + " below. Cite the sources you use inline, in the exact format [Source N]."
                        + " If the sources do not contain enough information to answer the question,"
                        + " say you don't know rather than guessing or using outside knowledge.\n");
        if (language != null && !language.isBlank()) {
            sb.append("Respond in ").append(language).append(".\n");
        } else {
            sb.append("Respond in the same language as the question.\n");
        }
        sb.append('\n');
        for (int i = 0; i < budgeted.size(); i++) {
            SearchResultChunk chunk = budgeted.get(i);
            sb.append("[Source ")
                    .append(i + 1)
                    .append(" — ")
                    .append(chunk.filename())
                    .append("]\n")
                    .append(chunk.content())
                    .append("\n\n");
        }
        return sb.toString();
    }

    private static List<Citation> toCitations(List<SearchResultChunk> budgeted) {
        List<Citation> citations = new ArrayList<>(budgeted.size());
        for (int i = 0; i < budgeted.size(); i++) {
            SearchResultChunk chunk = budgeted.get(i);
            citations.add(
                    new Citation(i + 1, chunk.chunkId(), chunk.documentId(), chunk.filename()));
        }
        return citations;
    }

    private ServerSentEvent<String> citationsEvent(List<Citation> citations) {
        try {
            return ServerSentEvent.<String>builder()
                    .event("citations")
                    .data(objectMapper.writeValueAsString(citations))
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize citations for SSE event", e);
        }
    }

    /**
     * Every knowledge_base gets this snapshot at creation time (Grooming #23) - a missing row here
     * is an invariant violation, not a client-facing condition, so it surfaces as a plain {@code
     * 500} via the generic exception handler rather than a translated {@code ApiException} (same
     * pattern as {@code HybridRetrievalService.requireModelConfig}).
     */
    private KnowledgeBaseModelConfig requireModelConfig(UUID knowledgeBaseId) {
        return knowledgeBaseModelConfigRepository
                .findByKnowledgeBase_Id(knowledgeBaseId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "knowledge_base "
                                                + knowledgeBaseId
                                                + " has no model config snapshot - should be"
                                                + " impossible, created together with the"
                                                + " knowledge_base"));
    }
}
