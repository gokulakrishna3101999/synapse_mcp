package com.synapsemcp.common;

/**
 * mcp_plan.md Stage 3, Grooming #20: the confirmed bucket-granularity dimension - a tenant's chat
 * usage (ask, rerank, vision extraction) and embedding usage (chunk embedding, KB-creation
 * dimension probe) draw from separate buckets even when both resolve to the same provider string,
 * so heavy ingestion can't starve that tenant's own Q&A and vice versa.
 */
public enum RateLimitKind {
    CHAT,
    EMBEDDING
}
