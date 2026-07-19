package com.synapsemcp.rag;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * rag_plan.md Stage 6b: per-chat-model context-window token budgets for {@code
 * RagAnsweringService}'s greedy chunk-inclusion step. A {@code Map} config shape doesn't fit this
 * codebase's usual {@code @Value("${prop:default}")} constructor-injection convention (every other
 * config value here is a single scalar) - {@code @ConfigurationProperties} is the first use of it
 * in this codebase, chosen because it's the idiomatic Spring Boot way to bind a map from YAML,
 * rather than working around {@code @Value}'s limitations for this one genuinely map-shaped need.
 *
 * <p>Keyed by chat model name (e.g. {@code gpt-4o}); a model absent from {@code
 * synapsemcp.rag.context-window-tokens} falls back to {@link #getDefaultContextWindowTokens()}
 * (plan's own stated default, 8000).
 */
@Component
@ConfigurationProperties(prefix = "synapsemcp.rag")
public class RagProperties {

    private Map<String, Integer> contextWindowTokens = new HashMap<>();
    private int defaultContextWindowTokens = 8000;

    public Map<String, Integer> getContextWindowTokens() {
        return new HashMap<>(contextWindowTokens);
    }

    public void setContextWindowTokens(Map<String, Integer> contextWindowTokens) {
        this.contextWindowTokens =
                contextWindowTokens == null ? new HashMap<>() : new HashMap<>(contextWindowTokens);
    }

    public int getDefaultContextWindowTokens() {
        return defaultContextWindowTokens;
    }

    public void setDefaultContextWindowTokens(int defaultContextWindowTokens) {
        this.defaultContextWindowTokens = defaultContextWindowTokens;
    }

    public int contextWindowTokensFor(String modelName) {
        return contextWindowTokens.getOrDefault(modelName, defaultContextWindowTokens);
    }
}
