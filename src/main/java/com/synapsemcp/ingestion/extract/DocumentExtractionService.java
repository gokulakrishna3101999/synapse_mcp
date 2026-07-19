package com.synapsemcp.ingestion.extract;

import com.synapsemcp.ingestion.extract.DocumentExtractor.ExtractionResult;
import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * rag_plan.md Stage 5a. Picks the first {@link DocumentExtractor} whose {@code supports(mimeType)}
 * is true, in Spring's injected bean order.
 */
@Service
public class DocumentExtractionService {

    private final List<DocumentExtractor> extractors;

    DocumentExtractionService(List<DocumentExtractor> extractors) {
        this.extractors = extractors;
    }

    public ExtractionResult extract(
            byte[] content, String mimeType, KnowledgeBaseModelConfig kbConfig) throws IOException {
        for (DocumentExtractor extractor : extractors) {
            if (extractor.supports(mimeType)) {
                return extractor.extract(content, mimeType, kbConfig);
            }
        }
        throw new UnsupportedOperationException(
                "no DocumentExtractor registered for mime type: " + mimeType);
    }
}
