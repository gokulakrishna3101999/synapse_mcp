package com.synapsemcp.ingestion.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * rag_plan.md Stage 5b: content-based (any format), {@code @Order(2)} - triggered by any {@code
 * ^#{1,6}\s+.*$} heading line, which every core extractor normalizes headings into ({@link
 * com.synapsemcp.ingestion.extract.HtmlExtractor}'s converted {@code <h1>}-{@code <h6>}, {@link
 * com.synapsemcp.ingestion.extract.OfficeExtractor}'s converted Word heading styles, and native
 * Markdown source), so this one regex works uniformly across Markdown/HTML/Word as the plan
 * requires. Heading lines update a per-level stack for {@code headingPath} metadata but are not
 * themselves included in chunk content - only the body text under each heading is. Consecutive
 * headings with no body between them never flush an empty chunk ("skips flushing heading-only
 * chunks") - the path just keeps updating until real content is seen.
 */
@Component
@Order(2)
public class StructureAwareChunkingStrategy implements ChunkingStrategy {

    private static final Pattern HEADING_LINE = Pattern.compile("^(#{1,6})\\s+(.*)$");

    @Override
    public boolean supports(String text, String mimeType) {
        return text.lines().anyMatch(line -> HEADING_LINE.matcher(line).matches());
    }

    @Override
    public List<ChunkData> chunk(String text, String mimeType) {
        List<ChunkData> chunks = new ArrayList<>();
        String[] headingByLevel = new String[7];
        StringBuilder body = new StringBuilder();
        String currentHeadingPath = null;
        int position = 0;

        for (String line : text.split("\n", -1)) {
            Matcher match = HEADING_LINE.matcher(line);
            if (match.matches()) {
                if (!body.isEmpty()) {
                    chunks.add(
                            new ChunkData(
                                    body.toString().strip(),
                                    position++,
                                    currentHeadingPath,
                                    "structure-aware"));
                    body.setLength(0);
                }
                int level = match.group(1).length();
                headingByLevel[level] = match.group(2).strip();
                for (int deeper = level + 1; deeper <= 6; deeper++) {
                    headingByLevel[deeper] = null;
                }
                currentHeadingPath = buildHeadingPath(headingByLevel);
            } else if (!line.isBlank()) {
                body.append(line).append('\n');
            }
        }
        if (!body.isEmpty()) {
            chunks.add(
                    new ChunkData(
                            body.toString().strip(),
                            position,
                            currentHeadingPath,
                            "structure-aware"));
        }
        return chunks;
    }

    private String buildHeadingPath(String[] headingByLevel) {
        String path =
                Stream.of(headingByLevel)
                        .skip(1)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining(" > "));
        return path.isEmpty() ? null : path;
    }
}
