package com.synapsemcp.ingestion.extract;

import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * rag_plan.md Stage 5a: Word/Excel/PowerPoint, both OOXML and legacy binary. All extraction APIs
 * verified via {@code javap} before use (first use of POI in this codebase) - including that {@code
 * WorkbookFactory.create(InputStream)} auto-detects `.xls` vs `.xlsx` and returns a common {@code
 * Workbook} interface, so spreadsheet extraction doesn't need a format branch at all.
 *
 * <p>Genuine structure preservation (heading-style paragraphs → {@code #} lines, sheet names → a
 * {@code # SheetName} heading per sheet, matching {@code TableAwareChunkingStrategy}'s expected
 * shape) is only implemented for `.docx`/`.xlsx`/`.pptx` - the modern, common-case formats. Legacy
 * `.doc`/`.ppt` fall back to POI's own simpler plain-text extraction utilities ({@code
 * WordExtractor}, shape text without heading detection) - a deliberate scope boundary given legacy
 * Office formats' comparative rarity and their extraction APIs' added complexity, not an oversight.
 *
 * <p>Decompression-bomb protection ({@code rag_plan.md} Stage 5a TODO) needs no extra wiring here:
 * confirmed via {@code javap} that POI's {@code ZipSecureFile} (used internally by every OOXML
 * reader path below) already defaults {@code MIN_INFLATE_RATIO = 0.01} (rejects &gt;100x
 * expansion), {@code MAX_ENTRY_SIZE} ≈ 4GB, and {@code MAX_FILE_COUNT = 1000} - active
 * automatically for any file opened through POI's standard APIs, not something this class has to
 * configure.
 */
@Component
@Order(Integer.MAX_VALUE - 1)
public class OfficeExtractor implements DocumentExtractor {

    private static final Set<String> WORD_MIME_TYPES =
            Set.of(
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final Set<String> EXCEL_MIME_TYPES =
            Set.of(
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final Set<String> POWERPOINT_MIME_TYPES =
            Set.of(
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String PPTX_MIME =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    /** Matches Word's default heading style IDs/names ("Heading1", "heading 1", ...). */
    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)heading\\s*([1-6])");

    @Override
    public boolean supports(String mimeType) {
        return WORD_MIME_TYPES.contains(mimeType)
                || EXCEL_MIME_TYPES.contains(mimeType)
                || POWERPOINT_MIME_TYPES.contains(mimeType);
    }

    @Override
    public ExtractionResult extract(
            byte[] content, String mimeType, KnowledgeBaseModelConfig kbConfig) throws IOException {
        if (EXCEL_MIME_TYPES.contains(mimeType)) {
            return extractSpreadsheet(content);
        }
        if (DOCX_MIME.equals(mimeType)) {
            return extractDocx(content);
        }
        if (WORD_MIME_TYPES.contains(mimeType)) {
            return extractLegacyDoc(content);
        }
        if (PPTX_MIME.equals(mimeType)) {
            return extractPptx(content);
        }
        return extractLegacyPpt(content);
    }

    private ExtractionResult extractDocx(byte[] content) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            StringBuilder text = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                appendHeadingAwareLine(text, paragraph.getStyle(), paragraph.getText());
            }
            return new ExtractionResult(text.toString().strip(), "OfficeExtractor(docx)");
        }
    }

    private ExtractionResult extractLegacyDoc(byte[] content) throws IOException {
        try (WordExtractor extractor = new WordExtractor(new ByteArrayInputStream(content))) {
            return new ExtractionResult(extractor.getText().strip(), "OfficeExtractor(doc-legacy)");
        }
    }

    private ExtractionResult extractSpreadsheet(byte[] content) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            DataFormatter formatter = new DataFormatter();
            StringBuilder text = new StringBuilder();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                text.append("# ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    String rowText = formatRow(row, formatter);
                    if (!rowText.isEmpty()) {
                        text.append(rowText).append('\n');
                    }
                }
            }
            return new ExtractionResult(text.toString().strip(), "OfficeExtractor(spreadsheet)");
        }
    }

    private String formatRow(Row row, DataFormatter formatter) {
        StringBuilder rowText = new StringBuilder();
        for (Cell cell : row) {
            String value = formatter.formatCellValue(cell);
            if (!value.isBlank()) {
                if (!rowText.isEmpty()) {
                    rowText.append('\t');
                }
                rowText.append(value);
            }
        }
        return rowText.toString();
    }

    private ExtractionResult extractPptx(byte[] content) throws IOException {
        try (XMLSlideShow slideShow = new XMLSlideShow(new ByteArrayInputStream(content))) {
            StringBuilder text = new StringBuilder();
            int slideNumber = 1;
            for (XSLFSlide slide : slideShow.getSlides()) {
                text.append("# Slide ").append(slideNumber++).append('\n');
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        appendIfNotBlank(text, textShape.getText());
                    }
                }
            }
            return new ExtractionResult(text.toString().strip(), "OfficeExtractor(pptx)");
        }
    }

    private ExtractionResult extractLegacyPpt(byte[] content) throws IOException {
        try (HSLFSlideShow slideShow = new HSLFSlideShow(new ByteArrayInputStream(content))) {
            StringBuilder text = new StringBuilder();
            int slideNumber = 1;
            for (HSLFSlide slide : slideShow.getSlides()) {
                text.append("# Slide ").append(slideNumber++).append('\n');
                for (HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape textShape) {
                        appendIfNotBlank(text, textShape.getText());
                    }
                }
            }
            return new ExtractionResult(text.toString().strip(), "OfficeExtractor(ppt-legacy)");
        }
    }

    private void appendHeadingAwareLine(StringBuilder text, String style, String paragraphText) {
        if (paragraphText == null || paragraphText.isBlank()) {
            return;
        }
        Matcher headingMatch = style != null ? HEADING_STYLE.matcher(style) : null;
        if (headingMatch != null && headingMatch.find()) {
            int level = Integer.parseInt(headingMatch.group(1));
            text.append("#".repeat(level)).append(' ').append(paragraphText).append('\n');
        } else {
            text.append(paragraphText).append('\n');
        }
    }

    private void appendIfNotBlank(StringBuilder text, String value) {
        if (value != null && !value.isBlank()) {
            text.append(value).append('\n');
        }
    }
}
