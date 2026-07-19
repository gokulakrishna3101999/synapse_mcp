package com.synapsemcp.ingestion.extract;

import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * rag_plan.md Stage 5a: {@code application/pdf}, LLM-vision-first with a PDFBox text fallback -
 * user-confirmed full plan spec (asked, not guessed, given the alternative simpler PDFBox-only
 * approach would have been materially lower-risk/-cost). Renders each page with {@link
 * PDFRenderer#renderImageWithDPI} at {@link #renderDpi}, batches {@link #pagesPerVisionCall} pages
 * per {@link VisionTranscriptionService} call, concatenates each batch's response in page order.
 *
 * <p>Falls back to {@link PDFTextStripper} (library-only, no model call) in three cases: the
 * document has more than {@link #maxLlmPages} pages (a cost guardrail - skips the LLM path
 * entirely, never even attempts it), the vision path throws for any reason, or the vision path's
 * concatenated result is blank - all three failure modes are treated identically, matching this
 * project's established "fail open to the cheaper/safer path" convention ({@code
 * LlmRerankerService}'s reranking fallback, the embedding cache's Redis fail-open). A PDF with no
 * embedded text layer at all (a scanned document) and a vision-path failure will still end up with
 * blank text from the fallback too - that's not special-cased here, it flows into {@code
 * IngestionPipelineService}'s existing "no extractable text content" job-failure path (Grooming
 * #69), the same as any other extractor producing nothing.
 *
 * <p>24h Redis-cached by content hash ({@link ExtractionCacheService}) - re-extracting identical
 * bytes (e.g. a Grooming #26 retry-via-reupload) never repeats a real, billed vision call.
 */
@Component
public class PdfExtractor implements DocumentExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfExtractor.class);
    private static final String CACHE_NAMESPACE = "pdf-extract";
    private static final String VISION_INSTRUCTION =
            "Transcribe all text visible on the following page(s) of a document, in order.";

    private final VisionTranscriptionService visionTranscriptionService;
    private final ExtractionCacheService cacheService;
    private final int maxLlmPages;
    private final int pagesPerVisionCall;
    private final float renderDpi;

    PdfExtractor(
            VisionTranscriptionService visionTranscriptionService,
            ExtractionCacheService cacheService,
            @Value("${synapsemcp.extraction.pdf.max-llm-pages:100}") int maxLlmPages,
            @Value("${synapsemcp.extraction.pdf.pages-per-vision-call:5}") int pagesPerVisionCall,
            @Value("${synapsemcp.extraction.pdf.render-dpi:150}") float renderDpi) {
        this.visionTranscriptionService = visionTranscriptionService;
        this.cacheService = cacheService;
        this.maxLlmPages = maxLlmPages;
        this.pagesPerVisionCall = pagesPerVisionCall;
        this.renderDpi = renderDpi;
    }

    @Override
    public boolean supports(String mimeType) {
        return "application/pdf".equals(mimeType);
    }

    @Override
    public ExtractionResult extract(
            byte[] content, String mimeType, KnowledgeBaseModelConfig kbConfig) throws IOException {
        String cached = cacheService.get(CACHE_NAMESPACE, content);
        if (cached != null) {
            return new ExtractionResult(cached, "PdfExtractor(cached)");
        }

        try (PDDocument document = Loader.loadPDF(content)) {
            int pageCount = document.getNumberOfPages();
            ExtractionResult result =
                    pageCount > maxLlmPages
                            ? fallback(document, "PdfExtractor(pdfbox-fallback, page-limit)")
                            : tryVisionThenFallback(document, kbConfig, pageCount);
            if (!result.text().isBlank()) {
                cacheService.put(CACHE_NAMESPACE, content, result.text());
            }
            return result;
        }
    }

    private ExtractionResult tryVisionThenFallback(
            PDDocument document, KnowledgeBaseModelConfig kbConfig, int pageCount)
            throws IOException {
        try {
            String visionText = extractViaVision(document, kbConfig, pageCount);
            if (!visionText.isBlank()) {
                return new ExtractionResult(visionText, "PdfExtractor(llm)");
            }
            log.info(
                    "PDF vision extraction returned blank text - falling back to PDFBox text extraction");
        } catch (Exception e) {
            log.warn("PDF vision extraction failed - falling back to PDFBox text extraction", e);
        }
        return fallback(document, "PdfExtractor(pdfbox-fallback)");
    }

    private String extractViaVision(
            PDDocument document, KnowledgeBaseModelConfig kbConfig, int pageCount)
            throws IOException {
        PDFRenderer renderer = new PDFRenderer(document);
        StringBuilder text = new StringBuilder();
        for (int batchStart = 0; batchStart < pageCount; batchStart += pagesPerVisionCall) {
            int batchEnd = Math.min(batchStart + pagesPerVisionCall, pageCount);
            List<byte[]> pageImages = new ArrayList<>(batchEnd - batchStart);
            for (int page = batchStart; page < batchEnd; page++) {
                pageImages.add(renderPageAsPng(renderer, page));
            }
            String batchText =
                    visionTranscriptionService.transcribe(kbConfig, VISION_INSTRUCTION, pageImages);
            if (!batchText.isBlank()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(batchText.strip());
            }
        }
        return text.toString();
    }

    private byte[] renderPageAsPng(PDFRenderer renderer, int pageIndex) throws IOException {
        var image = renderer.renderImageWithDPI(pageIndex, renderDpi);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private ExtractionResult fallback(PDDocument document, String extractorName)
            throws IOException {
        String text = new PDFTextStripper().getText(document).strip();
        return new ExtractionResult(text, extractorName);
    }
}
