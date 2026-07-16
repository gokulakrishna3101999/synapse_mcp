package com.synapsemcp.common;

/**
 * Shared {@code documents.status} / {@code ingestion_jobs.status} state machine (rag_plan.md Stage
 * 0.5, Grooming #30). {@code ingestion_jobs} is the authoritative record; {@code documents.status}
 * is a same-transaction mirror. A CHECK constraint on both columns is added by {@link
 * com.synapsemcp.config.AnnIndexBootstrapRunner}, since plain JPA has no CHECK annotation.
 */
public enum IngestionStatus {
    PENDING,
    INDEXING,
    READY,
    FAILED
}
