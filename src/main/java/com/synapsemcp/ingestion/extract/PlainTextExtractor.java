package com.synapsemcp.ingestion.extract;

import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** rag_plan.md Stage 5a: {@code text/plain} and {@code text/markdown} - already flat text. */
@Component
@Order(Integer.MAX_VALUE - 1)
public class PlainTextExtractor implements DocumentExtractor {

    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of("text/plain", "text/markdown");

    @Override
    public boolean supports(String mimeType) {
        return SUPPORTED_MIME_TYPES.contains(mimeType);
    }

    @Override
    public ExtractionResult extract(
            byte[] content, String mimeType, KnowledgeBaseModelConfig kbConfig) {
        return new ExtractionResult(
                new String(content, StandardCharsets.UTF_8), "PlainTextExtractor");
    }
}
