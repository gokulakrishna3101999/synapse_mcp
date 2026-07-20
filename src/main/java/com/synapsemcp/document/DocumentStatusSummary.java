package com.synapsemcp.document;

import java.util.List;

/**
 * Document-count-by-status rollup for one knowledge_base (mcp_plan.md Stage 2, `get_tenant`/{@code
 * list_knowledge_bases}). Explicit fields rather than a {@code Map<IngestionStatus, Long>} -
 * matches this codebase's preference for typed DTOs over dynamic maps for domain concepts.
 */
public record DocumentStatusSummary(long pending, long indexing, long ready, long failed) {

    public static final DocumentStatusSummary EMPTY = new DocumentStatusSummary(0, 0, 0, 0);

    /**
     * Folds one {@code GROUP BY status} query result (for a single knowledge_base) into a summary.
     */
    public static DocumentStatusSummary from(
            List<? extends DocumentRepository.StatusCount> counts) {
        long pending = 0;
        long indexing = 0;
        long ready = 0;
        long failed = 0;
        for (DocumentRepository.StatusCount count : counts) {
            switch (count.getStatus()) {
                case PENDING -> pending = count.getCount();
                case INDEXING -> indexing = count.getCount();
                case READY -> ready = count.getCount();
                case FAILED -> failed = count.getCount();
            }
        }
        return new DocumentStatusSummary(pending, indexing, ready, failed);
    }

    public long total() {
        return pending + indexing + ready + failed;
    }

    public DocumentStatusSummary plus(DocumentStatusSummary other) {
        return new DocumentStatusSummary(
                pending + other.pending,
                indexing + other.indexing,
                ready + other.ready,
                failed + other.failed);
    }
}
