package com.synapsemcp.ingestion.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class PdfExtractorTest {

    private final VisionTranscriptionService visionService = mock(VisionTranscriptionService.class);
    private final ExtractionCacheService cacheService = mock(ExtractionCacheService.class);
    private final KnowledgeBaseModelConfig kbConfig =
            KnowledgeBaseModelConfig.create(
                    null, "openai", "gpt-4o", "openai", "text-embedding-3-small", "creds");

    private PdfExtractor extractor(int maxLlmPages) {
        return new PdfExtractor(visionService, cacheService, maxLlmPages, 5, 150f);
    }

    private static byte[] onePagePdfWithText(String text) throws IOException {
        return pdfWithPages(1, text);
    }

    private static byte[] pdfWithPages(int pageCount, String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText(text + " page " + i);
                    stream.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void cacheHitReturnsCachedTextWithoutCallingVisionOrFallback() throws IOException {
        byte[] pdf = onePagePdfWithText("hello");
        when(cacheService.get("pdf-extract", pdf)).thenReturn("previously cached text");

        DocumentExtractor.ExtractionResult result =
                extractor(100).extract(pdf, "application/pdf", kbConfig);

        assertThat(result.text()).isEqualTo("previously cached text");
        assertThat(result.extractorName()).isEqualTo("PdfExtractor(cached)");
        org.mockito.Mockito.verifyNoInteractions(visionService);
    }

    @Test
    void visionSuccessIsUsedAndCached() throws IOException {
        byte[] pdf = onePagePdfWithText("hello");
        when(cacheService.get(anyString(), any())).thenReturn(null);
        when(visionService.transcribe(any(), anyString(), anyList()))
                .thenReturn("vision transcribed text");

        DocumentExtractor.ExtractionResult result =
                extractor(100).extract(pdf, "application/pdf", kbConfig);

        assertThat(result.text()).isEqualTo("vision transcribed text");
        assertThat(result.extractorName()).isEqualTo("PdfExtractor(llm)");
        verify(cacheService).put("pdf-extract", pdf, "vision transcribed text");
    }

    @Test
    void visionFailureFallsBackToPdfBoxTextExtraction() throws IOException {
        byte[] pdf = onePagePdfWithText("real embedded text");
        when(cacheService.get(anyString(), any())).thenReturn(null);
        when(visionService.transcribe(any(), anyString(), anyList()))
                .thenThrow(new RuntimeException("provider error"));

        DocumentExtractor.ExtractionResult result =
                extractor(100).extract(pdf, "application/pdf", kbConfig);

        assertThat(result.text()).contains("real embedded text");
        assertThat(result.extractorName()).isEqualTo("PdfExtractor(pdfbox-fallback)");
    }

    @Test
    void visionBlankResultFallsBackToPdfBoxTextExtraction() throws IOException {
        byte[] pdf = onePagePdfWithText("real embedded text");
        when(cacheService.get(anyString(), any())).thenReturn(null);
        when(visionService.transcribe(any(), anyString(), anyList())).thenReturn("   ");

        DocumentExtractor.ExtractionResult result =
                extractor(100).extract(pdf, "application/pdf", kbConfig);

        assertThat(result.text()).contains("real embedded text");
        assertThat(result.extractorName()).isEqualTo("PdfExtractor(pdfbox-fallback)");
    }

    @Test
    void documentAbovePageLimitSkipsVisionEntirely() throws IOException {
        byte[] pdf = pdfWithPages(3, "content");
        when(cacheService.get(anyString(), any())).thenReturn(null);

        DocumentExtractor.ExtractionResult result =
                extractor(2).extract(pdf, "application/pdf", kbConfig);

        assertThat(result.extractorName()).isEqualTo("PdfExtractor(pdfbox-fallback, page-limit)");
        assertThat(result.text()).contains("content");
        org.mockito.Mockito.verify(visionService, never())
                .transcribe(any(), anyString(), anyList());
    }

    @Test
    void supportsOnlyApplicationPdf() {
        PdfExtractor extractor = extractor(100);
        assertThat(extractor.supports("application/pdf")).isTrue();
        assertThat(extractor.supports("text/plain")).isFalse();
        assertThat(extractor.supports("image/png")).isFalse();
    }
}
