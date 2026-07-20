package com.synapsemcp.rag.retrieve;

import com.synapsemcp.chat.ChatModelFactory;
import com.synapsemcp.common.RateLimitKind;
import com.synapsemcp.common.RateLimited;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

/**
 * rag_plan.md Stage 6a+: reranking, implemented as an LLM-reranker rather than the plan's first
 * preference (an open-source ONNX cross-encoder, e.g. {@code BAAI/bge-reranker-v2-m3}) - user
 * confirmed (`plan.md` §9 2026-07-18) this is the deliberate fallback path the plan itself already
 * names ("if open-source is not feasible at all due to cost or complexity, fall back to using the
 * tenant's configured LLM for reranking"), chosen after a real ONNX model was found to be
 * technically downloadable (a trusted `onnx-community` int8 export exists) but would have been this
 * codebase's first ML-runtime dependency, with real inference-correctness risk this environment
 * can't independently verify against a reference implementation.
 *
 * <p>Reranking is deliberately fail-open: any provider/parsing failure logs and returns the
 * candidates in their original (pre-rerank) order rather than failing the whole search/ask request
 * - reranking is an optional precision enhancement (rag_plan.md: "Reranking is optional and can be
 * disabled via a request parameter"), not a correctness requirement, so a broken reranker degrading
 * to "no reranking happened" is the right failure mode, matching this project's established Redis
 * fail-open philosophy applied to a different dependency. This does <b>not</b> extend to the
 * tenant's own {@link RateLimited} chat-call budget being exhausted (mcp_plan.md Stage 3, confirmed
 * via {@code AskUserQuestion}): a rate-limit denial is thrown by {@code RateLimitAspect} from
 * outside this method's own try/catch, surfacing as a clear {@code 429}/{@code Retry-After} on
 * {@code search}/{@code ask} rather than silently skipping reranking - a caller who wants to avoid
 * this can pass {@code rerank=false} to stay on vector/keyword-only retrieval, which never touches
 * a chat provider at all.
 *
 * <p>No live chat provider is available in this environment (recurring blocker, every stage) - the
 * response-parsing regex is deliberately lenient (tolerates an optional "Passage"/"Source" prefix
 * word and either {@code :}/{@code .}/{@code -} as the separator) since its behavior against a real
 * provider's actual output formatting couldn't be verified live here, only against a mocked {@link
 * ChatModel} returning hand-written sample responses.
 */
@Service
public class LlmRerankerService {

    private static final Logger log = LoggerFactory.getLogger(LlmRerankerService.class);

    /**
     * Bounds prompt size/cost - a reranker only needs enough of each passage to judge relevance.
     */
    private static final int MAX_CONTENT_CHARS_PER_CANDIDATE = 800;

    private static final Pattern SCORE_LINE =
            Pattern.compile(
                    "(?i)^\\W*(?:passage|source)?\\s*(\\d+)\\s*[:.\\-]\\s*(\\d+(?:\\.\\d+)?)");

    private final ChatModelFactory chatModelFactory;

    public LlmRerankerService(ChatModelFactory chatModelFactory) {
        this.chatModelFactory = chatModelFactory;
    }

    /**
     * @return {@code candidates} re-sorted by the reranker's relevance score (highest first), with
     *     each result's {@code score} replaced by that reranker score - or the original, unmodified
     *     list (same order, original retrieval scores) if reranking fails or returns nothing
     *     parseable.
     */
    @RateLimited(RateLimitKind.CHAT)
    public List<SearchResultChunk> rerank(
            KnowledgeBaseModelConfig kbConfig, String query, List<SearchResultChunk> candidates) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        try {
            ChatModel chatModel = chatModelFactory.getChatModelForKnowledgeBase(kbConfig);
            ChatOptions options = chatModelFactory.optionsForKnowledgeBase(kbConfig);
            List<Message> messages =
                    List.of(
                            new SystemMessage(systemPrompt()),
                            new UserMessage(userPrompt(query, candidates)));
            ChatResponse response = chatModel.call(new Prompt(messages, options));
            String text = response.getResult().getOutput().getText();
            Map<Integer, Double> scores = parseScores(text == null ? "" : text);
            return resort(candidates, scores);
        } catch (RuntimeException e) {
            log.warn("reranking failed - falling back to unreranked retrieval order", e);
            return candidates;
        }
    }

    private static String systemPrompt() {
        return "You are a relevance-scoring assistant for a search system. Given a question and a"
                + " numbered list of candidate passages, score how relevant each passage is to"
                + " answering the question, from 0 (irrelevant) to 100 (perfectly relevant)."
                + " Respond with exactly one line per passage, formatted as \"N: score\" (e.g."
                + " \"1: 87\"), in passage order, with no other text before, after, or between"
                + " the lines.";
    }

    private static String userPrompt(String query, List<SearchResultChunk> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("Question: ").append(query).append("\n\n");
        for (int i = 0; i < candidates.size(); i++) {
            String content = candidates.get(i).content();
            if (content.length() > MAX_CONTENT_CHARS_PER_CANDIDATE) {
                content = content.substring(0, MAX_CONTENT_CHARS_PER_CANDIDATE);
            }
            sb.append("Passage ").append(i + 1).append(": ").append(content).append("\n\n");
        }
        return sb.toString();
    }

    private static Map<Integer, Double> parseScores(String text) {
        Map<Integer, Double> scores = new HashMap<>();
        for (String line : text.split("\\R")) {
            Matcher matcher = SCORE_LINE.matcher(line.trim());
            if (matcher.find()) {
                scores.put(
                        Integer.parseInt(matcher.group(1)), Double.parseDouble(matcher.group(2)));
            }
        }
        return scores;
    }

    private static List<SearchResultChunk> resort(
            List<SearchResultChunk> candidates, Map<Integer, Double> scores) {
        if (scores.isEmpty()) {
            log.warn(
                    "reranker response had no parseable score lines - falling back to unreranked"
                            + " retrieval order");
            return candidates;
        }
        List<SearchResultChunk> rescored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            SearchResultChunk candidate = candidates.get(i);
            Double score = scores.get(i + 1);
            double newScore = score != null ? score : candidate.score();
            rescored.add(
                    new SearchResultChunk(
                            candidate.chunkId(),
                            candidate.documentId(),
                            candidate.filename(),
                            candidate.content(),
                            newScore,
                            candidate.metadata()));
        }
        rescored.sort(Comparator.comparingDouble(SearchResultChunk::score).reversed());
        return rescored;
    }
}
