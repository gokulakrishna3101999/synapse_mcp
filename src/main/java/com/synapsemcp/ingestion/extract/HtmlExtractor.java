package com.synapsemcp.ingestion.extract;

import com.synapsemcp.knowledgebase.KnowledgeBaseModelConfig;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * rag_plan.md Stage 5a: {@code text/html}. Converts {@code <h1>}-{@code <h6>} into Markdown-style
 * {@code #}-prefixed lines <i>before</i> stripping tags, so {@code
 * StructureAwareChunkingStrategy}'s single heading regex (designed for Markdown) also works on HTML
 * source, per the plan's explicit "works for Markdown/HTML/Word uniformly" requirement - jsoup's
 * own {@code text()} alone would flatten headings into indistinguishable plain text. Verified every
 * API used here via {@code javap} against jsoup 1.22.2 before relying on it (first use of jsoup in
 * this codebase).
 */
@Component
@Order(Integer.MAX_VALUE - 1)
public class HtmlExtractor implements DocumentExtractor {

    @Override
    public boolean supports(String mimeType) {
        return "text/html".equals(mimeType);
    }

    @Override
    public ExtractionResult extract(
            byte[] content, String mimeType, KnowledgeBaseModelConfig kbConfig) {
        Document doc = Jsoup.parse(new String(content, StandardCharsets.UTF_8));

        for (int level = 1; level <= 6; level++) {
            String marker = "#".repeat(level) + " ";
            for (Element heading : doc.select("h" + level)) {
                heading.text(marker + heading.text());
            }
        }

        // Marks every block-level element boundary with a literal "\n" text token before the
        // markup is stripped, so paragraph/heading/list-item boundaries survive as real newlines
        // instead of collapsing into jsoup's default single-space block separator - required for
        // StructureAwareChunkingStrategy's per-line heading regex to see each heading on its own
        // line.
        doc.select("br").after("\\n");
        doc.select("p, div, h1, h2, h3, h4, h5, h6, li, tr, blockquote").after("\\n");

        String withMarkers = doc.body().html();
        String stripped =
                Jsoup.clean(
                        withMarkers,
                        "",
                        Safelist.none(),
                        new Document.OutputSettings().prettyPrint(false));
        String text =
                Arrays.stream(stripped.replace("\\n", "\n").split("\n"))
                        .map(String::strip)
                        .filter(line -> !line.isEmpty())
                        .collect(Collectors.joining("\n"));

        return new ExtractionResult(text, "HtmlExtractor");
    }
}
