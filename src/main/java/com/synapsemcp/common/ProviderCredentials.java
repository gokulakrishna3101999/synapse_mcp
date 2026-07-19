package com.synapsemcp.common;

/**
 * JSON shape stored (Base64-encoded) in {@code model_configs.provider_credentials} and {@code
 * knowledge_base_model_configs.provider_credentials} (rag_plan.md Stage 2). Either field may be
 * null/blank - e.g. a tenant using only Ollama typically needs no API key at all.
 *
 * <p>{@link #toString()} is overridden to redact both keys - found during audit (`plan.md` §9,
 * 2026-07-17): a Java record's default {@code toString()} prints every component's raw value, so
 * this object would otherwise leak plaintext API keys into logs or exception messages the moment
 * any future code passed it to a logger, even accidentally (e.g. debugging a decode failure).
 * Nothing in the current codebase logs it directly, but the record shape makes that a one-line
 * mistake to introduce later with no compiler warning.
 */
public record ProviderCredentials(String chatApiKey, String embeddingApiKey) {

    @Override
    public String toString() {
        return "ProviderCredentials[chatApiKey=%s, embeddingApiKey=%s]"
                .formatted(redact(chatApiKey), redact(embeddingApiKey));
    }

    private static String redact(String apiKey) {
        return apiKey == null || apiKey.isBlank() ? "null" : "[REDACTED]";
    }
}
