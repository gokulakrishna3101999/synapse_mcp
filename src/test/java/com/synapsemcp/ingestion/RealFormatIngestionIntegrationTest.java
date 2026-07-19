package com.synapsemcp.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.AbstractIntegrationTest;
import com.synapsemcp.common.IngestionStatus;
import com.synapsemcp.document.UploadDocumentResponse;
import com.synapsemcp.embedding.EmbeddingModelFactory;
import com.synapsemcp.ingestion.index.LuceneIndexManager;
import com.synapsemcp.knowledgebase.CreateKnowledgeBaseRequest;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
import com.synapsemcp.rag.chunk.Chunk;
import com.synapsemcp.rag.chunk.ChunkRepository;
import com.synapsemcp.tenant.ConfigureModelRequest;
import com.synapsemcp.tenant.CreateTenantRequest;
import com.synapsemcp.tenant.CreateTenantResponse;
import com.synapsemcp.tenant.ModelConfigResponse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * A third "final thorough validation" pass (`plan.md` §9 2026-07-18) found a real, significant
 * coverage gap: no genuinely valid `.docx`/`.xlsx`/`.pptx`/HTML file had ever been uploaded through
 * the *full* live pipeline (real HTTP upload → Tika MIME detection → extraction → chunking → embed
 * → persist → index → `READY`) - `OfficeExtractor`/`HtmlExtractor`/`TableAwareChunkingStrategy`
 * were only ever verified in isolation (Stage 5's own build-time scratch probes, and unit tests
 * calling the chunking strategies directly with hand-written text already shaped the way an
 * extractor would produce it). The only binary-format integration test that existed before this
 * file (`IngestionPipelineEdgeCaseIntegrationTest`'s corrupt-`.docx` case) was deliberately
 * malformed, so it never exercised the real happy path either. Real fixtures generated here via
 * POI's own writer APIs, the same technique Stage 5's original build-time scratch probes used.
 */
class RealFormatIngestionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private com.synapsemcp.common.RedisKeyPrefix redisKeyPrefix;
    @Autowired private IngestionJobRepository ingestionJobRepository;
    @Autowired private ChunkRepository chunkRepository;
    @Autowired private LuceneIndexManager luceneIndexManager;

    @MockitoBean private EmbeddingModelFactory embeddingModelFactory;

    private record TenantFixture(UUID tenantId, String apiKey) {}

    @BeforeEach
    void stubEmbeddingModel() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.dimensions()).thenReturn(1536);
        when(embeddingModel.embed(anyList()))
                .thenAnswer(
                        invocation -> {
                            List<String> texts = invocation.getArgument(0);
                            List<float[]> vectors = new ArrayList<>();
                            for (int i = 0; i < texts.size(); i++) {
                                vectors.add(new float[1536]);
                            }
                            return vectors;
                        });
        when(embeddingModelFactory.getEmbeddingModel(any())).thenReturn(embeddingModel);
        when(embeddingModelFactory.getEmbeddingModelForKnowledgeBase(any()))
                .thenReturn(embeddingModel);
    }

    private TenantFixture createConfiguredTenant(String name) {
        Set<String> rateLimitKeys =
                redisTemplate.keys(redisKeyPrefix.key("rate_limit:tenant-create:*"));
        if (rateLimitKeys != null && !rateLimitKeys.isEmpty()) {
            redisTemplate.delete(rateLimitKeys);
        }
        CreateTenantResponse tenant =
                restTemplate.postForObject(
                        "/api/v1/tenants",
                        new CreateTenantRequest(name),
                        CreateTenantResponse.class);
        restTemplate.exchange(
                "/api/v1/tenants/{tenantId}/model-config",
                HttpMethod.PUT,
                new HttpEntity<>(
                        new ConfigureModelRequest(
                                "openai",
                                "gpt-4o",
                                "openai",
                                "text-embedding-3-small",
                                "sk-fake-chat-key",
                                "sk-fake-embed-key"),
                        bearerHeaders(tenant.apiKey())),
                ModelConfigResponse.class,
                tenant.tenantId());
        return new TenantFixture(tenant.tenantId(), tenant.apiKey());
    }

    private UUID createKnowledgeBase(String apiKey, String name) {
        ResponseEntity<KnowledgeBaseResponse> response =
                restTemplate.exchange(
                        "/api/v1/knowledgebase",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new CreateKnowledgeBaseRequest(name), bearerHeaders(apiKey)),
                        KnowledgeBaseResponse.class);
        return response.getBody().id();
    }

    private HttpHeaders bearerHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        return headers;
    }

    private ResponseEntity<UploadDocumentResponse> uploadDocument(
            String apiKey, UUID knowledgeBaseId, String filename, byte[] content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add(
                "file",
                new ByteArrayResource(content) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                });
        HttpHeaders headers = bearerHeaders(apiKey);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange(
                "/api/v1/knowledgebase/{id}/documents",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                UploadDocumentResponse.class,
                knowledgeBaseId);
    }

    private IngestionStatus awaitTerminalState(UUID jobId) {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            IngestionStatus status =
                    ingestionJobRepository.findById(jobId).orElseThrow().getStatus();
            if (status == IngestionStatus.READY || status == IngestionStatus.FAILED) {
                return status;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("job " + jobId + " did not reach a terminal state within 8s");
    }

    @Test
    void ingestsARealDocxWithHeadingsIntoStructureAwareChunksEndToEnd() throws Exception {
        byte[] docxBytes = buildRealDocx();
        TenantFixture tenant = createConfiguredTenant("Real docx tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "real-docx-kb");

        UploadDocumentResponse upload =
                uploadDocument(tenant.apiKey(), kbId, "report.docx", docxBytes).getBody();
        IngestionStatus finalStatus = awaitTerminalState(upload.jobId());

        assertThat(finalStatus).isEqualTo(IngestionStatus.READY);
        IngestionJob job = ingestionJobRepository.findById(upload.jobId()).orElseThrow();
        assertThat(job.getErrorDetail()).isNull();

        List<Chunk> chunks = chunkRepository.findByDocument_Id(upload.documentId());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks)
                .extracting(c -> (String) c.getMetadata().get("headingPath"))
                .as(
                        "real Word heading paragraphs must survive extraction as # lines and drive"
                                + " StructureAwareChunkingStrategy's headingPath metadata")
                .contains("Quarterly Report > Financial Summary");
        assertThat(chunks).allMatch(c -> c.getEmbedding1536() != null);
        Set<String> indexedIds = luceneIndexManager.listIndexedChunkIds(kbId);
        assertThat(indexedIds)
                .containsExactlyInAnyOrderElementsOf(
                        chunks.stream().map(c -> c.getId().toString()).collect(Collectors.toSet()));
    }

    @Test
    void ingestsARealXlsxIntoTableAwareChunksEndToEnd() throws Exception {
        byte[] xlsxBytes = buildRealXlsx();
        TenantFixture tenant = createConfiguredTenant("Real xlsx tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "real-xlsx-kb");

        UploadDocumentResponse upload =
                uploadDocument(tenant.apiKey(), kbId, "data.xlsx", xlsxBytes).getBody();
        IngestionStatus finalStatus = awaitTerminalState(upload.jobId());

        assertThat(finalStatus).isEqualTo(IngestionStatus.READY);
        List<Chunk> chunks = chunkRepository.findByDocument_Id(upload.documentId());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks)
                .extracting(Chunk::getContent)
                .as(
                        "TableAwareChunkingStrategy must have actually run (not the fallback), keeping"
                                + " the sheet heading and tab-separated cell data intact")
                .anyMatch(content -> content.contains("# Employees") && content.contains("Alice"));
        assertThat(chunks)
                .extracting(c -> (String) c.getMetadata().get("strategy"))
                .contains("table-aware");
        Set<String> indexedIds = luceneIndexManager.listIndexedChunkIds(kbId);
        assertThat(indexedIds)
                .containsExactlyInAnyOrderElementsOf(
                        chunks.stream().map(c -> c.getId().toString()).collect(Collectors.toSet()));
    }

    @Test
    void ingestsARealPptxEndToEnd() throws Exception {
        byte[] pptxBytes = buildRealPptx();
        TenantFixture tenant = createConfiguredTenant("Real pptx tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "real-pptx-kb");

        UploadDocumentResponse upload =
                uploadDocument(tenant.apiKey(), kbId, "deck.pptx", pptxBytes).getBody();
        IngestionStatus finalStatus = awaitTerminalState(upload.jobId());

        assertThat(finalStatus).isEqualTo(IngestionStatus.READY);
        List<Chunk> chunks = chunkRepository.findByDocument_Id(upload.documentId());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks)
                .extracting(Chunk::getContent)
                .anyMatch(content -> content.contains("Welcome to the presentation"));
    }

    @Test
    void ingestsRealHtmlWithHeadingsIntoStructureAwareChunksEndToEnd() throws Exception {
        // Long enough per section to clear DocumentChunkingService's single-chunk shortcut
        // (~1,228 extracted chars for the <1,000-token tier) - the extracted plain text (after
        // HtmlExtractor strips markup) is what's measured, not the raw HTML.
        String paragraphOne =
                "This paragraph has enough real content to avoid the shortcut. ".repeat(15);
        String paragraphTwo = "More body content describing the feature set in detail. ".repeat(15);
        String html =
                "<html><body>"
                        + "<h1>Product Overview</h1>"
                        + "<p>"
                        + paragraphOne
                        + "</p>"
                        + "<h2>Key Features</h2>"
                        + "<p>"
                        + paragraphTwo
                        + "</p>"
                        + "<ul><li>Feature one</li><li>Feature two</li></ul>"
                        + "</body></html>";
        TenantFixture tenant = createConfiguredTenant("Real html tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "real-html-kb");

        UploadDocumentResponse upload =
                uploadDocument(
                                tenant.apiKey(),
                                kbId,
                                "overview.html",
                                html.getBytes(StandardCharsets.UTF_8))
                        .getBody();
        IngestionStatus finalStatus = awaitTerminalState(upload.jobId());

        assertThat(finalStatus).isEqualTo(IngestionStatus.READY);
        List<Chunk> chunks = chunkRepository.findByDocument_Id(upload.documentId());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks)
                .extracting(c -> (String) c.getMetadata().get("headingPath"))
                .contains("Product Overview > Key Features");
        assertThat(chunks).anyMatch(c -> c.getContent().contains("Feature one"));
    }

    private static byte[] buildRealDocx() throws Exception {
        // Long enough per section to clear DocumentChunkingService's single-chunk shortcut
        // (~1,228 extracted chars for the <1,000-token tier).
        String introBody =
                "Overall performance was strong this quarter across every region. ".repeat(10);
        String financeBody =
                "Revenue grew year over year, driven primarily by new enterprise deals. "
                        .repeat(10);
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            addHeading(document, "Heading1", "Quarterly Report");
            addBody(document, introBody);
            addHeading(document, "Heading2", "Financial Summary");
            addBody(document, financeBody);
            document.write(out);
            return out.toByteArray();
        }
    }

    private static void addHeading(XWPFDocument document, String style, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle(style);
        XWPFRun run = paragraph.createRun();
        run.setText(text);
    }

    private static void addBody(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(text);
    }

    private static byte[] buildRealXlsx() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Employees");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Name");
            header.createCell(1).setCellValue("Department");
            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("Alice");
            row1.createCell(1).setCellValue("Engineering");
            Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("Bob");
            row2.createCell(1).setCellValue("Sales");
            // Enough additional rows to clear DocumentChunkingService's single-chunk shortcut
            // (~1,228 extracted chars for the <1,000-token tier) and force
            // TableAwareChunkingStrategy to actually produce multiple 512-char windows, not just
            // exercise the fallback with two trivial rows.
            for (int i = 0; i < 60; i++) {
                Row row = sheet.createRow(3 + i);
                row.createCell(0).setCellValue("Employee Number " + i);
                row.createCell(1).setCellValue("Department Of Something Reasonably Long " + i);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] buildRealPptx() throws Exception {
        try (XMLSlideShow slideShow = new XMLSlideShow();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFSlide slide = slideShow.createSlide();
            XSLFTextBox textBox = slide.createTextBox();
            textBox.setText("Welcome to the presentation");
            slideShow.write(out);
            return out.toByteArray();
        }
    }
}
