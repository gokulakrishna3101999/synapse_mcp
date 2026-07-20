package com.synapsemcp.mcp;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.rag.retrieve.SearchMode;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * mcp_plan.md Stage 2: shared bad-input-to-client-safe-error conversion for every tool taking a raw
 * id/mode string. {@code UUID.fromString}/{@code SearchMode.fromValue} both throw {@link
 * IllegalArgumentException} with a Java-internal message on bad input - this rethrows as {@link
 * ApiException} instead, matching every domain service's own convention, so the message the MCP
 * client sees is clean either way (Grooming #19).
 */
final class McpToolInputs {

    private McpToolInputs() {}

    static UUID parseUuid(String value, String fieldName) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Bad Request",
                    "invalid " + fieldName + ": not a valid id");
        }
    }

    static SearchMode parseSearchMode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return SearchMode.fromValue(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Bad Request",
                    "invalid mode: must be one of hybrid, vector, keyword");
        }
    }
}
