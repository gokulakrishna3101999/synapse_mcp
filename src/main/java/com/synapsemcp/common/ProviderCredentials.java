package com.synapsemcp.common;

/**
 * JSON shape stored (Base64-encoded) in {@code model_configs.provider_credentials} and {@code
 * knowledge_base_model_configs.provider_credentials} (rag_plan.md Stage 2). Either field may be
 * null/blank - e.g. a tenant using only Ollama typically needs no API key at all.
 */
public record ProviderCredentials(String chatApiKey, String embeddingApiKey) {}
