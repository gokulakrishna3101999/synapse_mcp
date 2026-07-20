package com.synapsemcp.document;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.IngestionStatus;
import com.synapsemcp.ingestion.IngestionJob;
import com.synapsemcp.ingestion.IngestionJobRepository;
import com.synapsemcp.ingestion.IngestionPipelineService;
import com.synapsemcp.knowledgebase.KnowledgeBase;
import com.synapsemcp.knowledgebase.KnowledgeBaseRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.apache.tika.Tika;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * rag_plan.md Stage 4: synchronous validation + idempotency + async dispatch for {@code POST
 * /api/v1/knowledgebase/{knowledgebaseId}/documents}. The actual extract/chunk/embed/persist/index
 * pipeline is Stage 5 - see {@link IngestionPipelineService}'s Javadoc. The uploaded bytes are
 * passed straight into the async dispatch call below rather than persisted anywhere first (user
 * confirmed, `plan.md` §9 2026-07-17 - see {@link IngestionPipelineService}'s Javadoc for why).
 */
@Service
public class DocumentUploadService {

    /**
     * The 7 direct-upload categories' canonical MIME strings (rag_plan.md's "Supported Document
     * Sources" table), verified empirically via real {@link Tika#detect(byte[], String)} calls
     * against representative fixture bytes for every extension - not guessed. Confirms two
     * non-obvious things: the OLE2-magic-byte ambiguity shared by legacy {@code .doc}/{@code
     * .xls}/{@code .ppt} and the bare-ZIP-magic-byte ambiguity shared by {@code .docx}/{@code
     * .xlsx}/{@code .pptx} are both correctly disambiguated by Tika's filename-hint fallback, and
     * {@code .md} content indistinguishable from plain text still resolves to {@code text/markdown}
     * given the filename.
     */
    private static final Set<String> SUPPORTED_MIME_TYPES =
            Set.of(
                    "application/pdf",
                    "image/jpeg",
                    "image/png",
                    "image/gif",
                    "image/bmp",
                    "image/tiff",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "text/html",
                    "text/plain",
                    "text/markdown");

    private static final String DEFAULT_FILENAME = "unnamed";
    private static final String DEFAULT_TEXT_FILENAME = "untitled.md";
    private static final String TEXT_MARKDOWN_MIME_TYPE = "text/markdown";

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final IngestionJobRepository ingestionJobRepository;
    private final IngestionPipelineService ingestionPipelineService;
    private final TransactionTemplate transactionTemplate;
    private final Tika tika = new Tika();

    public DocumentUploadService(
            KnowledgeBaseRepository knowledgeBaseRepository,
            DocumentRepository documentRepository,
            IngestionJobRepository ingestionJobRepository,
            IngestionPipelineService ingestionPipelineService,
            PlatformTransactionManager transactionManager) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.ingestionJobRepository = ingestionJobRepository;
        this.ingestionPipelineService = ingestionPipelineService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Ownership check, empty-file check, MIME detection, and content hashing all happen before any
     * lock is acquired or any row is written - a rejected upload never leaves orphaned {@code
     * documents}/{@code ingestion_jobs} rows (Stage 4's own "Done When" criteria), and the lock
     * below is held for the shortest possible window. The 20MB size cap is enforced earlier still,
     * by Spring's own multipart resolver ({@code spring.servlet.multipart.max-file-size}, `plan.md`
     * §9 2026-07-17) - an oversized file never reaches this method at all.
     *
     * <p>The idempotency check-then-write is wrapped in a pessimistic lock on the knowledge_base
     * row (found live, `plan.md` §9 2026-07-17 - the un-locked version let concurrent uploads of
     * identical new content race past the idempotency check, so N-1 of N simultaneous duplicate
     * uploads got a raw {@code 409} with no job info instead of the promised idempotent
     * short-circuit; concurrent retries of the same {@code FAILED} job all dispatched onto the
     * async pipeline simultaneously). Dispatch itself happens strictly <b>after</b> the locked
     * transaction commits (via the returned response's {@code dispatched()} flag, checked outside
     * {@link #transactionTemplate}'s callback) - never from inside it, or the async pipeline could
     * start before the transaction that created its rows has actually committed.
     */
    public UploadDocumentResponse uploadDocument(
            UUID tenantId, UUID knowledgeBaseId, MultipartFile file) {
        requireOwnedKnowledgeBase(tenantId, knowledgeBaseId);

        if (file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Bad Request", "uploaded file is empty");
        }

        byte[] content = readBytes(file);
        String filename = normalizeFilename(file.getOriginalFilename(), DEFAULT_FILENAME);
        return upload(tenantId, knowledgeBaseId, content, filename, null);
    }

    /**
     * mcp_plan.md Stage 2 {@code ingest} tool's file-bytes shape (the caller has already
     * Base64-decoded {@code content_base64} itself - there is no multipart resolver on the MCP
     * transport to do that, or to enforce a size cap, so the tool layer must do both explicitly).
     * Runs the same Tika sniffing {@link #uploadDocument} does.
     */
    public UploadDocumentResponse uploadBytes(
            UUID tenantId, UUID knowledgeBaseId, byte[] content, String filename) {
        requireOwnedKnowledgeBase(tenantId, knowledgeBaseId);
        return upload(
                tenantId,
                knowledgeBaseId,
                content,
                normalizeFilename(filename, DEFAULT_FILENAME),
                null);
    }

    /**
     * mcp_plan.md Stage 2 {@code ingest} tool's raw-{@code text} shape (rag_plan.md Grooming #28) -
     * wrapped as a synthetic {@code .md} document with {@code mimeType} hard-set to {@code
     * text/markdown}, skipping Tika sniffing entirely since the content's nature is already known.
     */
    public UploadDocumentResponse uploadRawText(
            UUID tenantId, UUID knowledgeBaseId, String text, String filename) {
        requireOwnedKnowledgeBase(tenantId, knowledgeBaseId);
        byte[] content = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        return upload(
                tenantId,
                knowledgeBaseId,
                content,
                normalizeFilename(filename, DEFAULT_TEXT_FILENAME),
                TEXT_MARKDOWN_MIME_TYPE);
    }

    /**
     * Shared core once ownership has already been pre-checked by the caller: {@code
     * mimeTypeOverride == null} runs Tika detection ({@link #uploadDocument}/{@link #uploadBytes});
     * a non-null value (the raw-text shape) skips detection entirely.
     */
    private UploadDocumentResponse upload(
            UUID tenantId,
            UUID knowledgeBaseId,
            byte[] content,
            String filename,
            String mimeTypeOverride) {
        if (content.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Bad Request", "content is empty");
        }

        String mimeType =
                mimeTypeOverride != null ? mimeTypeOverride : tika.detect(content, filename);
        if (!SUPPORTED_MIME_TYPES.contains(mimeType)) {
            throw new ApiException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Unsupported Media Type",
                    "unsupported file type: " + mimeType);
        }
        String contentHash = ContentHasher.sha256Hex(content);

        UploadDocumentResponse response =
                transactionTemplate.execute(
                        status ->
                                resolveUnderLock(
                                        tenantId,
                                        knowledgeBaseId,
                                        filename,
                                        mimeType,
                                        contentHash));

        if (response.dispatched()) {
            ingestionPipelineService.run(response.jobId(), content);
        }
        return response;
    }

    private UploadDocumentResponse resolveUnderLock(
            UUID tenantId,
            UUID knowledgeBaseId,
            String filename,
            String mimeType,
            String contentHash) {
        KnowledgeBase knowledgeBase = requireOwnedKnowledgeBase(tenantId, knowledgeBaseId);
        knowledgeBaseRepository.lockById(knowledgeBaseId);

        return documentRepository
                .findByTenantIdAndKnowledgeBase_IdAndContentHash(
                        tenantId, knowledgeBaseId, contentHash)
                .map(this::resolveExisting)
                .orElseGet(
                        () ->
                                createNewDocument(
                                        tenantId, knowledgeBase, filename, mimeType, contentHash));
    }

    private UploadDocumentResponse createNewDocument(
            UUID tenantId,
            KnowledgeBase knowledgeBase,
            String filename,
            String mimeType,
            String contentHash) {
        Document document =
                documentRepository.save(
                        Document.create(
                                tenantId,
                                knowledgeBase,
                                filename,
                                mimeType,
                                IngestionStatus.PENDING,
                                contentHash));
        IngestionJob job =
                ingestionJobRepository.save(
                        IngestionJob.create(tenantId, document, IngestionStatus.PENDING));
        return new UploadDocumentResponse(
                document.getId(), job.getId(), IngestionStatus.PENDING, true);
    }

    /**
     * Grooming #6b/#26: {@code PENDING}/{@code INDEXING}/{@code READY} short-circuits as a pure
     * no-op - {@code dispatched=false}, the caller confirmed via {@code AskUserQuestion} that this
     * maps to {@code 200}, not {@code 202}, since no new work was actually triggered. {@code
     * FAILED} is instead reset in place - {@code dispatched=true}, the same {@code documentId}/
     * {@code jobId} as before, genuinely re-entering the pipeline (dispatched by the caller once
     * this locked transaction commits), mapped to {@code 202}.
     */
    private UploadDocumentResponse resolveExisting(Document document) {
        IngestionJob job =
                ingestionJobRepository
                        .findByDocument_Id(document.getId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Document "
                                                        + document.getId()
                                                        + " has no ingestion_jobs row - Grooming"
                                                        + " #6b guarantees exactly one"));

        if (document.getStatus() != IngestionStatus.FAILED) {
            return new UploadDocumentResponse(
                    document.getId(), job.getId(), document.getStatus(), false);
        }

        document.setStatus(IngestionStatus.PENDING);
        job.setStatus(IngestionStatus.PENDING);
        job.setErrorDetail(null);
        job.setStage(null);
        documentRepository.save(document);
        ingestionJobRepository.save(job);
        return new UploadDocumentResponse(
                document.getId(), job.getId(), IngestionStatus.PENDING, true);
    }

    /**
     * A knowledge_base belonging to a different tenant is treated identically to a nonexistent id -
     * both {@code 404} - matching {@code KnowledgeBaseService.requireOwnedKnowledgeBase}'s existing
     * contract exactly. Called twice per upload - once as a fast, unlocked pre-check (so an
     * invalid/foreign knowledge_base id never reaches the lock at all), and again inside the locked
     * transaction to get a fresh, transaction-scoped entity reference to build a new {@code
     * Document} against (an entity loaded outside the transaction can't safely be reused inside
     * it).
     */
    private KnowledgeBase requireOwnedKnowledgeBase(UUID tenantId, UUID knowledgeBaseId) {
        return knowledgeBaseRepository
                .findByIdAndTenant_Id(knowledgeBaseId, tenantId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        HttpStatus.NOT_FOUND,
                                        "Not Found",
                                        "knowledge base not found"));
    }

    private static String normalizeFilename(String originalFilename, String defaultFilename) {
        return originalFilename == null || originalFilename.isBlank()
                ? defaultFilename
                : originalFilename;
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "Bad Request", "failed to read uploaded file");
        }
    }
}
