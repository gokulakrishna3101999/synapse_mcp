package com.synapsemcp.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Base64-encodes/decodes {@link ProviderCredentials} for storage (rag_plan.md Stage 2, `plan.md` §9
 * 2026-07-16) - an app-layer obfuscation transform, not encryption: no key material, trivially
 * reversible by anyone with DB access, a deliberate tradeoff over reintroducing AES-GCM/key
 * management.
 */
public final class ProviderCredentialsCodec {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ProviderCredentialsCodec() {}

    public static String encode(ProviderCredentials credentials) {
        try {
            byte[] json = OBJECT_MAPPER.writeValueAsBytes(credentials);
            return Base64.getEncoder().encodeToString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize provider credentials", e);
        }
    }

    public static ProviderCredentials decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return new ProviderCredentials(null, null);
        }
        try {
            byte[] json = Base64.getDecoder().decode(encoded.getBytes(StandardCharsets.UTF_8));
            return OBJECT_MAPPER.readValue(json, ProviderCredentials.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decode provider credentials", e);
        }
    }
}
