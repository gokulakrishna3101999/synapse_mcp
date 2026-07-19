package com.synapsemcp.document;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hashing for {@code documents.content_hash} (rag_plan.md Stage 4, Grooming #6b) - the
 * idempotency key for the upload short-circuit. Same style as {@link
 * com.synapsemcp.tenant.ApiKeyHasher}, just over raw file bytes rather than a UTF-8 string.
 */
public final class ContentHasher {

    private ContentHasher() {}

    public static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
