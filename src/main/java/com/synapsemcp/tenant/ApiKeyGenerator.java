package com.synapsemcp.tenant;

import java.security.SecureRandom;
import java.util.Base64;

/** Generates the raw, high-entropy API key shown once to the caller (rag_plan.md Stage 1). */
public final class ApiKeyGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int KEY_BYTES = 32;

    private ApiKeyGenerator() {}

    public static String generate() {
        byte[] bytes = new byte[KEY_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
