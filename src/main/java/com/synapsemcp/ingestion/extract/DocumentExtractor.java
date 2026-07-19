package com.synapsemcp.ingestion.extract;

import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import java.io.IOException;

/**
 * rag_plan.md Stage 5a SPI - each implementation handles one or more MIME types. {@code
 * DocumentExtractionService} dispatches to the first extractor whose {@link #supports(String)}
 * returns true, in Spring bean registration order (matching {@code @Order}-controlled dispatch used
 * elsewhere in this codebase, e.g. {@code ChunkingStrategy}).
 *
 * <p>Where structure exists in the source (headings, table rows), extractors normalize it into
 * plain Markdown-style {@code #}/{@code ##}/... heading lines - the same convention regardless of
 * source format - so {@code StructureAwareChunkingStrategy}'s single heading-detection regex works
 * uniformly across HTML/Word/Markdown, per the plan's own "works for Markdown/HTML/Word uniformly"
 * requirement, rather than needing a format-specific structure detector for each source type.
 *
 * <p>{@code kbConfig} was added alongside {@code PdfExtractor}/{@code ImageExtractor} (both
 * LLM-vision-first) - they need it to resolve the knowledge_base's locked chat model via {@code
 * ChatModelFactory#getChatModelForKnowledgeBase}, the same snapshot-based resolution every other
 * knowledge_base-scoped provider call in this codebase already uses (Grooming #23). The three
 * pre-existing library-only extractors (text/HTML/Office) ignore the parameter entirely - their own
 * extraction logic is unchanged.
 */
public interface DocumentExtractor {

    boolean supports(String mimeType);

    ExtractionResult extract(byte[] content, String mimeType, KnowledgeBaseModelConfig kbConfig)
            throws IOException;

    /**
     * @param extractorName a short, stable label recorded verbatim to {@code
     *     documents.extractor_name} for observability (e.g. {@code "PlainTextExtractor"}).
     */
    record ExtractionResult(String text, String extractorName) {}
}
