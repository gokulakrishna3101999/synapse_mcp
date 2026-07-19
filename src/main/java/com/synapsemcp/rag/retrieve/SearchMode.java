package com.synapsemcp.rag.retrieve;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * rag_plan.md Stage 6a: the {@code search} endpoint's {@code mode} request field - {@code hybrid}
 * (default, BM25 + vector merged via RRF), {@code vector} (ANN only), {@code keyword} (BM25 only -
 * the only mode that never embeds the query, so it works even without a usable embedding provider).
 * JSON values are lower-case; an unrecognized value throws {@link IllegalArgumentException} from
 * {@link #fromValue}, which Jackson wraps as {@code HttpMessageNotReadableException} - already
 * mapped to {@code 400} by {@code ApiExceptionHandler} (Grooming #6, Stage 0), so no new exception
 * handling is needed for a malformed mode value.
 */
public enum SearchMode {
    HYBRID,
    VECTOR,
    KEYWORD;

    @JsonCreator
    public static SearchMode fromValue(String value) {
        return SearchMode.valueOf(value.toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String toValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
