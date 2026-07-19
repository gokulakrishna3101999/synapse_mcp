package com.synapsemcp.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.AbstractIntegrationTest;
import com.synapsemcp.chat.ChatModelFactory;
import com.synapsemcp.common.IngestionStatus;
import com.synapsemcp.document.Document;
import com.synapsemcp.document.DocumentRepository;
import com.synapsemcp.document.UploadDocumentResponse;
import com.synapsemcp.embedding.EmbeddingModelFactory;
import com.synapsemcp.knowledgebase.CreateKnowledgeBaseRequest;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
import com.synapsemcp.rag.chunk.Chunk;
import com.synapsemcp.rag.chunk.ChunkRepository;
import com.synapsemcp.tenant.ConfigureModelRequest;
import com.synapsemcp.tenant.CreateTenantRequest;
import com.synapsemcp.tenant.CreateTenantResponse;
import com.synapsemcp.tenant.ModelConfigResponse;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
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
 * User-requested build of {@code PdfExtractor}/{@code ImageExtractor} (previously deferred,
 * Grooming #61) - full plan spec (LLM-vision-first + library fallback for both), user-confirmed
 * over the simpler library-only alternative. This test drives the real HTTP upload → extraction →
 * chunk → embed → persist → index → {@code READY} pipeline for real PDF/PNG fixtures, with {@code
 * ChatModelFactory} mocked (no live credentials available in CI) so it's deterministic - the vision
 * path itself was separately verified live against real Google GenAI credentials before this file
 * was written (real "The capital of France is Paris." PDF/JPEG fixtures, correctly transcribed and
 * correctly answerable via a live {@code /ask} call).
 */
class PdfImageIngestionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private com.synapsemcp.common.RedisKeyPrefix redisKeyPrefix;
    @Autowired private IngestionJobRepository ingestionJobRepository;
    @Autowired private ChunkRepository chunkRepository;
    @Autowired private DocumentRepository documentRepository;

    @MockitoBean private EmbeddingModelFactory embeddingModelFactory;
    @MockitoBean private ChatModelFactory chatModelFactory;

    private record TenantFixture(UUID tenantId, String apiKey) {}

    @BeforeEach
    void stubModels() {
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
        when(chatModelFactory.optionsForKnowledgeBase(any()))
                .thenReturn(ChatOptions.builder().model("gpt-4o").build());
    }

    private void stubVisionResponse(String text) {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
        when(chatModelFactory.getChatModelForKnowledgeBase(any())).thenReturn(chatModel);
    }

    private void stubVisionFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("provider unavailable"));
        when(chatModelFactory.getChatModelForKnowledgeBase(any())).thenReturn(chatModel);
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
        long deadline = System.currentTimeMillis() + 10_000;
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
        throw new AssertionError("job " + jobId + " did not reach a terminal state within 10s");
    }

    // 300x220 - above ImageExtractor's 200px minimum-dimension floor (Grooming #81) on both axes,
    // so these fixtures still exercise the vision-first path rather than being skipped straight to
    // OCR the way a genuinely small/thin image now deliberately is.
    private static byte[] realPngWithVisualText(String text) throws Exception {
        BufferedImage img = new BufferedImage(300, 220, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 300, 220);
        g.setColor(Color.BLACK);
        g.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 20));
        g.drawString(text, 10, 45);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] realPdfWithEmbeddedText(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void realImageIngestsEndToEndViaVisionPath() throws Exception {
        stubVisionResponse("Golden retrievers are friendly dogs.");
        byte[] png = realPngWithVisualText("placeholder - vision mocked");
        TenantFixture tenant = createConfiguredTenant("Image vision tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "image-vision-kb");

        UploadDocumentResponse upload =
                uploadDocument(tenant.apiKey(), kbId, "photo.png", png).getBody();
        IngestionStatus finalStatus = awaitTerminalState(upload.jobId());

        assertThat(finalStatus).isEqualTo(IngestionStatus.READY);
        Document document = documentRepository.findById(upload.documentId()).orElseThrow();
        assertThat(document.getExtractorName()).isEqualTo("ImageExtractor(llm)");
        List<Chunk> chunks = chunkRepository.findByDocument_Id(upload.documentId());
        assertThat(chunks)
                .extracting(Chunk::getContent)
                .anyMatch(c -> c.contains("Golden retrievers"));
    }

    @Test
    void realImageFallsBackToRealOcrWhenVisionFails() throws Exception {
        stubVisionFailure();
        byte[] png = realPngWithVisualText("Hello World");
        TenantFixture tenant = createConfiguredTenant("Image ocr tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "image-ocr-kb");

        UploadDocumentResponse upload =
                uploadDocument(tenant.apiKey(), kbId, "photo2.png", png).getBody();
        IngestionStatus finalStatus = awaitTerminalState(upload.jobId());

        assertThat(finalStatus).isEqualTo(IngestionStatus.READY);
        Document document = documentRepository.findById(upload.documentId()).orElseThrow();
        assertThat(document.getExtractorName()).isEqualTo("ImageExtractor(ocr-fallback)");
        List<Chunk> chunks = chunkRepository.findByDocument_Id(upload.documentId());
        assertThat(chunks)
                .extracting(Chunk::getContent)
                .anyMatch(
                        c ->
                                c.toLowerCase().contains("hello")
                                        && c.toLowerCase().contains("world"));
    }

    @Test
    void realPdfIngestsEndToEndViaVisionPath() throws Exception {
        stubVisionResponse("SynapseMCP is a multi-tenant RAG platform.");
        byte[] pdf = realPdfWithEmbeddedText("placeholder - vision mocked");
        TenantFixture tenant = createConfiguredTenant("Pdf vision tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "pdf-vision-kb");

        UploadDocumentResponse upload =
                uploadDocument(tenant.apiKey(), kbId, "doc.pdf", pdf).getBody();
        IngestionStatus finalStatus = awaitTerminalState(upload.jobId());

        assertThat(finalStatus).isEqualTo(IngestionStatus.READY);
        Document document = documentRepository.findById(upload.documentId()).orElseThrow();
        assertThat(document.getExtractorName()).isEqualTo("PdfExtractor(llm)");
        List<Chunk> chunks = chunkRepository.findByDocument_Id(upload.documentId());
        assertThat(chunks)
                .extracting(Chunk::getContent)
                .anyMatch(c -> c.contains("multi-tenant RAG platform"));
    }

    @Test
    void realPdfFallsBackToPdfBoxTextExtractionWhenVisionFails() throws Exception {
        stubVisionFailure();
        byte[] pdf = realPdfWithEmbeddedText("Real embedded PDF text content");
        TenantFixture tenant = createConfiguredTenant("Pdf fallback tenant");
        UUID kbId = createKnowledgeBase(tenant.apiKey(), "pdf-fallback-kb");

        UploadDocumentResponse upload =
                uploadDocument(tenant.apiKey(), kbId, "doc2.pdf", pdf).getBody();
        IngestionStatus finalStatus = awaitTerminalState(upload.jobId());

        assertThat(finalStatus).isEqualTo(IngestionStatus.READY);
        Document document = documentRepository.findById(upload.documentId()).orElseThrow();
        assertThat(document.getExtractorName()).isEqualTo("PdfExtractor(pdfbox-fallback)");
        List<Chunk> chunks = chunkRepository.findByDocument_Id(upload.documentId());
        assertThat(chunks)
                .extracting(Chunk::getContent)
                .anyMatch(c -> c.contains("Real embedded PDF text content"));
    }
}
