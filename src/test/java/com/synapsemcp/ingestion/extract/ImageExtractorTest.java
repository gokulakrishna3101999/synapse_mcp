package com.synapsemcp.ingestion.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageExtractorTest {

    private final VisionTranscriptionService visionService = mock(VisionTranscriptionService.class);
    private final RapidOcrService rapidOcrService = mock(RapidOcrService.class);
    private final ExtractionCacheService cacheService = mock(ExtractionCacheService.class);
    private final ImageExtractor extractor =
            new ImageExtractor(visionService, rapidOcrService, cacheService, 200);
    private final KnowledgeBaseModelConfig kbConfig =
            KnowledgeBaseModelConfig.create(
                    null, "openai", "gpt-4o", "openai", "text-embedding-3-small", "creds");

    // Above the extractor's 200px minimum-dimension floor, so vision is attempted.
    private static byte[] realPng() throws IOException {
        return pngOfSize(300, 300);
    }

    // Below the extractor's 200px minimum-dimension floor (Grooming #81) - vision must be skipped.
    private static byte[] tinyPng() throws IOException {
        return pngOfSize(50, 50);
    }

    private static byte[] pngOfSize(int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void cacheHitReturnsCachedTextWithoutCallingVisionOrOcr() throws IOException {
        byte[] png = realPng();
        when(cacheService.get("image-extract", png)).thenReturn("cached text");

        DocumentExtractor.ExtractionResult result = extractor.extract(png, "image/png", kbConfig);

        assertThat(result.text()).isEqualTo("cached text");
        assertThat(result.extractorName()).isEqualTo("ImageExtractor(cached)");
        org.mockito.Mockito.verifyNoInteractions(visionService, rapidOcrService);
    }

    @Test
    void visionSuccessIsUsedAndCachedWithoutFallingBackToOcr() throws IOException {
        byte[] png = realPng();
        when(cacheService.get(anyString(), any())).thenReturn(null);
        when(visionService.transcribe(any(), anyString(), anyList())).thenReturn("vision text");

        DocumentExtractor.ExtractionResult result = extractor.extract(png, "image/png", kbConfig);

        assertThat(result.text()).isEqualTo("vision text");
        assertThat(result.extractorName()).isEqualTo("ImageExtractor(llm)");
        verify(cacheService)
                .put(
                        org.mockito.ArgumentMatchers.eq("image-extract"),
                        any(),
                        org.mockito.ArgumentMatchers.eq("vision text"));
        org.mockito.Mockito.verifyNoInteractions(rapidOcrService);
    }

    @Test
    void visionFailureFallsBackToOcr() throws IOException {
        byte[] png = realPng();
        when(cacheService.get(anyString(), any())).thenReturn(null);
        when(visionService.transcribe(any(), anyString(), anyList()))
                .thenThrow(new RuntimeException("provider error"));
        when(rapidOcrService.runOcr(any())).thenReturn("ocr text");

        DocumentExtractor.ExtractionResult result = extractor.extract(png, "image/png", kbConfig);

        assertThat(result.text()).isEqualTo("ocr text");
        assertThat(result.extractorName()).isEqualTo("ImageExtractor(ocr-fallback)");
    }

    @Test
    void visionBlankResultFallsBackToOcr() throws IOException {
        byte[] png = realPng();
        when(cacheService.get(anyString(), any())).thenReturn(null);
        when(visionService.transcribe(any(), anyString(), anyList())).thenReturn("  ");
        when(rapidOcrService.runOcr(any())).thenReturn("ocr text");

        DocumentExtractor.ExtractionResult result = extractor.extract(png, "image/png", kbConfig);

        assertThat(result.extractorName()).isEqualTo("ImageExtractor(ocr-fallback)");
    }

    @Test
    void imageBelowMinDimensionSkipsVisionAndGoesStraightToOcr() throws IOException {
        byte[] png = tinyPng();
        when(cacheService.get(anyString(), any())).thenReturn(null);
        when(rapidOcrService.runOcr(any())).thenReturn("ocr text");

        DocumentExtractor.ExtractionResult result = extractor.extract(png, "image/png", kbConfig);

        assertThat(result.text()).isEqualTo("ocr text");
        assertThat(result.extractorName()).isEqualTo("ImageExtractor(ocr-fallback, min-size)");
        org.mockito.Mockito.verifyNoInteractions(visionService);
    }

    @Test
    void malformedImageBytesThrowIOException() {
        when(cacheService.get(anyString(), any())).thenReturn(null);

        assertThatThrownBy(() -> extractor.extract(new byte[] {1, 2, 3}, "image/png", kbConfig))
                .isInstanceOf(IOException.class);
        org.mockito.Mockito.verify(visionService, never())
                .transcribe(any(), anyString(), anyList());
    }

    @Test
    void supportsAllFiveImageMimeTypes() {
        assertThat(extractor.supports("image/jpeg")).isTrue();
        assertThat(extractor.supports("image/png")).isTrue();
        assertThat(extractor.supports("image/gif")).isTrue();
        assertThat(extractor.supports("image/bmp")).isTrue();
        assertThat(extractor.supports("image/tiff")).isTrue();
        assertThat(extractor.supports("application/pdf")).isFalse();
        assertThat(extractor.supports("text/plain")).isFalse();
    }
}
