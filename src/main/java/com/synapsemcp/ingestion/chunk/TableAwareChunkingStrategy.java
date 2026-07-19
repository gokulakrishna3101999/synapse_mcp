package com.synapsemcp.ingestion.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * rag_plan.md Stage 5b: spreadsheet MIME only, {@code @Order(1)} - runs before {@code
 * StructureAwareChunkingStrategy} because {@code OfficeExtractor}'s spreadsheet output also
 * contains {@code # SheetName} heading lines, which would otherwise trip the structure strategy
 * instead. Expects the exact shape {@code OfficeExtractor} produces: a {@code # SheetName} line per
 * sheet, followed by one line per row (tab-separated cells).
 */
@Component
@Order(1)
public class TableAwareChunkingStrategy implements ChunkingStrategy {

    private static final int WINDOW_CHARS = 512;
    private static final Set<String> SPREADSHEET_MIME_TYPES =
            Set.of(
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    @Override
    public boolean supports(String text, String mimeType) {
        return SPREADSHEET_MIME_TYPES.contains(mimeType);
    }

    @Override
    public List<ChunkData> chunk(String text, String mimeType) {
        List<ChunkData> chunks = new ArrayList<>();
        String currentSheet = null;
        List<String> pendingRows = new ArrayList<>();
        int pendingLength = 0;
        int position = 0;

        for (String line : text.split("\n", -1)) {
            if (line.startsWith("# ")) {
                position = flush(chunks, currentSheet, pendingRows, position);
                currentSheet = line.substring(2).strip();
                pendingLength = 0;
                continue;
            }
            if (line.isBlank()) {
                continue;
            }
            if (pendingLength + line.length() > WINDOW_CHARS && !pendingRows.isEmpty()) {
                position = flush(chunks, currentSheet, pendingRows, position);
                pendingLength = 0;
            }
            pendingRows.add(line);
            pendingLength += line.length();
        }
        flush(chunks, currentSheet, pendingRows, position);
        return chunks;
    }

    /**
     * @return the next chunk position to use - unchanged if {@code rows} was empty.
     */
    private int flush(List<ChunkData> chunks, String sheetName, List<String> rows, int position) {
        if (rows.isEmpty()) {
            return position;
        }
        String content = "# " + sheetName + "\n" + String.join("\n", rows);
        chunks.add(new ChunkData(content, position, sheetName, "table-aware"));
        rows.clear();
        return position + 1;
    }
}
