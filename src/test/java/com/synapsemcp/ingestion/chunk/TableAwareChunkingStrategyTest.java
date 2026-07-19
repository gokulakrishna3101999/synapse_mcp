package com.synapsemcp.ingestion.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.synapsemcp.ingestion.chunk.ChunkingStrategy.ChunkData;
import java.util.List;
import org.junit.jupiter.api.Test;

class TableAwareChunkingStrategyTest {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String XLS_MIME = "application/vnd.ms-excel";

    private final TableAwareChunkingStrategy strategy = new TableAwareChunkingStrategy();

    @Test
    void supportsOnlySpreadsheetMimeTypes() {
        assertThat(strategy.supports("anything", XLSX_MIME)).isTrue();
        assertThat(strategy.supports("anything", XLS_MIME)).isTrue();
        assertThat(strategy.supports("anything", "text/plain")).isFalse();
    }

    @Test
    void groupsRowsUnderTheirSheetHeadingIntoASingleChunkWhenSmall() {
        String text = "# Sheet1\nname\tage\nalice\t30\nbob\t25\n";

        List<ChunkData> chunks = strategy.chunk(text, XLSX_MIME);

        assertThat(chunks).hasSize(1);
        ChunkData chunk = chunks.get(0);
        assertThat(chunk.content()).isEqualTo("# Sheet1\nname\tage\nalice\t30\nbob\t25");
        assertThat(chunk.headingPath()).isEqualTo("Sheet1");
        assertThat(chunk.strategy()).isEqualTo("table-aware");
        assertThat(chunk.position()).isZero();
    }

    @Test
    void resetsTheWindowAtEachNewSheetBoundary() {
        String text = "# Sheet1\nrow1\nrow2\n# Sheet2\nrowA\nrowB\n";

        List<ChunkData> chunks = strategy.chunk(text, XLSX_MIME);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).headingPath()).isEqualTo("Sheet1");
        assertThat(chunks.get(0).content()).isEqualTo("# Sheet1\nrow1\nrow2");
        assertThat(chunks.get(1).headingPath()).isEqualTo("Sheet2");
        assertThat(chunks.get(1).content()).isEqualTo("# Sheet2\nrowA\nrowB");
        assertThat(chunks).extracting(ChunkData::position).containsExactly(0, 1);
    }

    @Test
    void startsANewWindowOnceThe512CharThresholdIsExceeded() {
        StringBuilder text = new StringBuilder("# BigSheet\n");
        String row = "a".repeat(100);
        for (int i = 0; i < 10; i++) {
            text.append(row).append('\n');
        }

        List<ChunkData> chunks = strategy.chunk(text.toString(), XLSX_MIME);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allMatch(c -> c.headingPath().equals("BigSheet"));
        assertThat(chunks).allMatch(c -> c.content().startsWith("# BigSheet\n"));
    }

    @Test
    void skipsBlankLines() {
        String text = "# Sheet1\nrow1\n\n\nrow2\n";

        List<ChunkData> chunks = strategy.chunk(text, XLSX_MIME);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("# Sheet1\nrow1\nrow2");
    }
}
