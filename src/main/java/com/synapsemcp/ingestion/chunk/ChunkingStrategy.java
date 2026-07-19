package com.synapsemcp.ingestion.chunk;

import java.util.List;

/**
 * rag_plan.md Stage 5b SPI. {@code DocumentChunkingService} dispatches to the first strategy whose
 * {@link #supports} is true, in Spring's {@code @Order}-controlled bean order - {@code
 * TableAwareChunkingStrategy} (1), {@code StructureAwareChunkingStrategy} (2), {@code
 * FixedSizeChunkingStrategy} ({@code LOWEST_PRECEDENCE}, universal fallback).
 */
public interface ChunkingStrategy {

    boolean supports(String text, String mimeType);

    List<ChunkData> chunk(String text, String mimeType);

    /**
     * @param headingPath breadcrumb-style structural location (e.g. {@code "Title > Subsection"} or
     *     a sheet name) for later citation - {@code null} when the strategy has no structural
     *     information to offer (e.g. {@code FixedSizeChunkingStrategy}).
     * @param strategy which strategy produced this chunk, recorded for observability.
     */
    record ChunkData(String content, int position, String headingPath, String strategy) {}
}
