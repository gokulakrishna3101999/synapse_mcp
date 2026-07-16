package com.synapsemcp.tenant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hashing for {@code api_keys.key_hash} (rag_plan.md Stage 0). API keys are high-entropy
 * machine-generated secrets, where a fast hash is an acceptable, deliberate choice - deliberately
 * distinct from the slow/salted {@code BCryptPasswordEncoder} used for human-chosen {@code
 * mcp_users} passwords (`mcp_plan.md` Stage 1).
 */
public final class ApiKeyHasher {

    private ApiKeyHasher() {}

    public static String sha256Hex(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
