package com.synapsemcp.ingestion.extract;

import com.benjaminwan.ocrlibrary.OcrResult;
import io.github.mymonstercat.Model;
import io.github.mymonstercat.ocr.InferenceEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * rag_plan.md Stage 5a: {@link ImageExtractor}'s OCR fallback - {@code io.github.mymonstercat}'s
 * pure-JVM ONNX PP-OCRv4 port ({@code Model.ONNX_PPOCR_V4}, confirmed via {@code javap} plus a real
 * OCR smoke test before adding the dependency - a hand-rendered "Golden Retriever" PNG correctly
 * recognized end to end). User-confirmed to build this (asked, not guessed) over the lower-risk
 * LLM-vision-only alternative.
 *
 * <p>Two real constraints found via the same verification, not assumed: (1) the published jar's
 * {@code runOcr} only accepts a file path, not raw bytes - this wraps every call in a temp file,
 * always deleted afterward; (2) {@code InferenceEngine.getInstance(Model)} is a process-wide
 * singleton with undocumented concurrent-call safety, and this app's {@code ingestionExecutor} pool
 * runs up to 8 documents in parallel - every call is serialized through a single lock, the same
 * defensive pattern already used for {@code LuceneIndexManager}'s per-knowledge_base {@code
 * IndexWriter} lock (a different native/single-writer resource, same underlying reasoning). The
 * engine itself is only constructed lazily on first real OCR fallback, not at app startup, so a
 * deployment that never hits this path never pays the model-load cost.
 */
@Component
public class RapidOcrService {

    private static final Object ENGINE_LOCK = new Object();

    /**
     * @param pngBytes a single image, already PNG-encoded.
     * @return recognized text, never null (blank if nothing was recognized).
     */
    public String runOcr(byte[] pngBytes) throws IOException {
        Path tempFile = Files.createTempFile("synapsemcp-ocr-", ".png");
        try {
            Files.write(tempFile, pngBytes);
            synchronized (ENGINE_LOCK) {
                InferenceEngine engine = InferenceEngine.getInstance(Model.ONNX_PPOCR_V4);
                OcrResult result = engine.runOcr(tempFile.toAbsolutePath().toString());
                String text = result.getStrRes();
                return text == null ? "" : text.trim();
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
