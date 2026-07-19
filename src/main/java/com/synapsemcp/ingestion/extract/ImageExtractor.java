package com.synapsemcp.ingestion.extract;

import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * rag_plan.md Stage 5a: {@code image/jpeg}, {@code image/png}, {@code image/gif}, {@code
 * image/bmp}, {@code image/tiff} - LLM-vision-first, falling back to {@link RapidOcrService} on
 * error/blank. User-confirmed full plan spec (asked, not guessed - RapidOCR is a brand-new
 * ML-runtime dependency, the same risk category already deferred once for the reranker, Grooming
 * #74).
 *
 * <p>Every input format is normalized to PNG once up front via {@code ImageIO} (confirmed via a
 * direct probe that this JDK's bundled {@code ImageIO} plugins already read/write all five formats
 * including TIFF - no extra image-codec dependency needed) - both the vision call and the OCR
 * fallback then operate on the same normalized bytes, rather than needing per-format handling
 * twice. The plan's "converting BMP/TIFF → PNG" note is about the vision call specifically (most
 * vision APIs only accept common web image formats); normalizing every format up front is a strict
 * superset of that requirement and keeps this class's logic uniform.
 *
 * <p>Images with either dimension below {@link #minDimensionPx} skip the vision call entirely and
 * go straight to OCR (Grooming #81, found live: a small/thin real image made a real vision call
 * return non-blank filler text - {@code "-"}, an em dash, or the literal sentence "No readable
 * text." - which is indistinguishable from genuine content to a simple blank check, silently
 * indexing garbage instead of falling back. A controlled A/B isolated this to image resolution, not
 * an RGBA/format bug - the identical content at 3x the linear size transcribed correctly every
 * time). Mirrors {@link PdfExtractor}'s own page-limit skip - both treat "known to be unreliable up
 * front" the same as a runtime vision failure, just without ever attempting the billed call.
 *
 * <p>24h Redis-cached by content hash ({@link ExtractionCacheService}), same convention as {@link
 * PdfExtractor} - re-extracting identical bytes never repeats a real, billed vision call or OCR
 * pass.
 */
@Component
public class ImageExtractor implements DocumentExtractor {

    private static final Logger log = LoggerFactory.getLogger(ImageExtractor.class);
    private static final String CACHE_NAMESPACE = "image-extract";
    private static final String VISION_INSTRUCTION =
            "Transcribe all text visible in the following image.";
    private static final Set<String> SUPPORTED_MIME_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif", "image/bmp", "image/tiff");

    private final VisionTranscriptionService visionTranscriptionService;
    private final RapidOcrService rapidOcrService;
    private final ExtractionCacheService cacheService;
    private final int minDimensionPx;

    ImageExtractor(
            VisionTranscriptionService visionTranscriptionService,
            RapidOcrService rapidOcrService,
            ExtractionCacheService cacheService,
            @Value("${synapsemcp.extraction.image.min-dimension-px:200}") int minDimensionPx) {
        this.visionTranscriptionService = visionTranscriptionService;
        this.rapidOcrService = rapidOcrService;
        this.cacheService = cacheService;
        this.minDimensionPx = minDimensionPx;
    }

    @Override
    public boolean supports(String mimeType) {
        return SUPPORTED_MIME_TYPES.contains(mimeType);
    }

    @Override
    public ExtractionResult extract(
            byte[] content, String mimeType, KnowledgeBaseModelConfig kbConfig) throws IOException {
        String cached = cacheService.get(CACHE_NAMESPACE, content);
        if (cached != null) {
            return new ExtractionResult(cached, "ImageExtractor(cached)");
        }

        NormalizedImage normalized = normalizeToPng(content);
        ExtractionResult result = tryVisionThenOcr(normalized, kbConfig);
        if (!result.text().isBlank()) {
            cacheService.put(CACHE_NAMESPACE, content, result.text());
        }
        return result;
    }

    private ExtractionResult tryVisionThenOcr(
            NormalizedImage normalized, KnowledgeBaseModelConfig kbConfig) throws IOException {
        if (normalized.width() < minDimensionPx || normalized.height() < minDimensionPx) {
            log.info(
                    "image dimensions {}x{} below the {}px minimum for reliable vision"
                            + " transcription - skipping vision, using OCR directly",
                    normalized.width(),
                    normalized.height(),
                    minDimensionPx);
            return ocrResult(normalized.png(), "ImageExtractor(ocr-fallback, min-size)");
        }
        try {
            String visionText =
                    visionTranscriptionService.transcribe(
                            kbConfig, VISION_INSTRUCTION, List.of(normalized.png()));
            if (!visionText.isBlank()) {
                return new ExtractionResult(visionText, "ImageExtractor(llm)");
            }
            log.info("image vision extraction returned blank text - falling back to OCR");
        } catch (Exception e) {
            log.warn("image vision extraction failed - falling back to OCR", e);
        }
        return ocrResult(normalized.png(), "ImageExtractor(ocr-fallback)");
    }

    private ExtractionResult ocrResult(byte[] png, String extractorName) throws IOException {
        String ocrText = rapidOcrService.runOcr(png).strip();
        return new ExtractionResult(ocrText, extractorName);
    }

    private static NormalizedImage normalizeToPng(byte[] content) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
        if (image == null) {
            throw new IOException("could not decode image content");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new NormalizedImage(out.toByteArray(), image.getWidth(), image.getHeight());
    }

    private record NormalizedImage(byte[] png, int width, int height) {}
}
