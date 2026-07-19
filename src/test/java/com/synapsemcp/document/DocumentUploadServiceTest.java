package com.synapsemcp.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.IngestionStatus;
import com.synapsemcp.ingestion.IngestionJob;
import com.synapsemcp.ingestion.IngestionJobRepository;
import com.synapsemcp.ingestion.IngestionPipelineService;
import com.synapsemcp.knowledgebase.KnowledgeBase;
import com.synapsemcp.knowledgebase.KnowledgeBaseRepository;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class DocumentUploadServiceTest {

    private final KnowledgeBaseRepository knowledgeBaseRepository =
            mock(KnowledgeBaseRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final IngestionJobRepository ingestionJobRepository =
            mock(IngestionJobRepository.class);
    private final IngestionPipelineService ingestionPipelineService =
            mock(IngestionPipelineService.class);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);

    private DocumentUploadService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID knowledgeBaseId = UUID.randomUUID();
    private KnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        service =
                new DocumentUploadService(
                        knowledgeBaseRepository,
                        documentRepository,
                        ingestionJobRepository,
                        ingestionPipelineService,
                        transactionManager);

        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        knowledgeBase = mock(KnowledgeBase.class);
        when(knowledgeBaseRepository.findByIdAndTenant_Id(knowledgeBaseId, tenantId))
                .thenReturn(Optional.of(knowledgeBase));
        when(documentRepository.findByTenantIdAndKnowledgeBase_IdAndContentHash(
                        any(), any(), any()))
                .thenReturn(Optional.empty());
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ingestionJobRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private MockMultipartFile plainTextFile(String content) {
        return new MockMultipartFile(
                "file", "notes.txt", "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void throws404WhenTheKnowledgeBaseIsNotOwnedByTheCaller() {
        when(knowledgeBaseRepository.findByIdAndTenant_Id(knowledgeBaseId, tenantId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.uploadDocument(
                                        tenantId, knowledgeBaseId, plainTextFile("hello")))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void throws400ForAnEmptyFile() {
        MockMultipartFile empty =
                new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        assertThatThrownBy(() -> service.uploadDocument(tenantId, knowledgeBaseId, empty))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /**
     * Content-sniffed, not extension-trusted (rag_plan.md Stage 4's own example): a {@code .zip}
     * with no recognizable magic bytes and no supported-extension fallback lands on Tika's generic
     * {@code application/octet-stream}, which is not in the allowlist.
     */
    @Test
    void throws415ForAnUnsupportedFileType() {
        MockMultipartFile exe =
                new MockMultipartFile(
                        "file",
                        "payload.bin",
                        "application/octet-stream",
                        new byte[] {0x01, 0x02, 0x03, (byte) 0xFF});

        assertThatThrownBy(() -> service.uploadDocument(tenantId, knowledgeBaseId, exe))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).getStatus())
                                        .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
    }

    @Test
    void doesNotWriteAnyRowWhenValidationFails() {
        MockMultipartFile exe =
                new MockMultipartFile(
                        "file",
                        "payload.bin",
                        "application/octet-stream",
                        new byte[] {0x01, 0x02, 0x03, (byte) 0xFF});

        assertThatThrownBy(() -> service.uploadDocument(tenantId, knowledgeBaseId, exe))
                .isInstanceOf(ApiException.class);

        verify(documentRepository, never()).save(any());
        verify(ingestionJobRepository, never()).save(any());
    }

    @Test
    void createsANewDocumentAndJobAndDispatchesForFreshContent() {
        UploadDocumentResponse response =
                service.uploadDocument(tenantId, knowledgeBaseId, plainTextFile("hello world"));

        assertThat(response.status()).isEqualTo(IngestionStatus.PENDING);
        assertThat(response.dispatched()).isTrue();
        verify(documentRepository).save(any());
        verify(ingestionJobRepository).save(any());
        verify(ingestionPipelineService)
                .run(response.jobId(), "hello world".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Guards the fix for a real race found live (`plan.md` §9, 2026-07-17): concurrent uploads of
     * identical new content raced past the idempotency check-then-insert with no synchronization,
     * so N-1 of N simultaneous duplicate uploads got a raw {@code 409} with no job info instead of
     * the promised idempotent short-circuit. User confirmed (recommended option) a pessimistic lock
     * on the knowledge_base row, serializing uploads to the same knowledge_base only. This unit
     * test can only confirm the lock call happens - the actual concurrency guarantee is proven by
     * {@code DocumentControllerIntegrationTest}'s real concurrent-thread test against a real
     * database, since Postgres row-locking can't be meaningfully exercised against a mock.
     */
    @Test
    void acquiresAPessimisticLockOnTheKnowledgeBaseBeforeResolvingIdempotency() {
        service.uploadDocument(tenantId, knowledgeBaseId, plainTextFile("hello world"));

        verify(knowledgeBaseRepository).lockById(knowledgeBaseId);
    }

    @Test
    void shortCircuitsWithoutDispatchingForAnExistingPendingDocument() {
        Document existing =
                Document.create(
                        tenantId,
                        knowledgeBase,
                        "notes.txt",
                        "text/plain",
                        IngestionStatus.PENDING,
                        "irrelevant-for-this-test");
        when(documentRepository.findByTenantIdAndKnowledgeBase_IdAndContentHash(
                        any(), any(), any()))
                .thenReturn(Optional.of(existing));
        IngestionJob existingJob = IngestionJob.create(tenantId, existing, IngestionStatus.PENDING);
        when(ingestionJobRepository.findByDocument_Id(existing.getId()))
                .thenReturn(Optional.of(existingJob));

        UploadDocumentResponse response =
                service.uploadDocument(tenantId, knowledgeBaseId, plainTextFile("hello world"));

        assertThat(response.status()).isEqualTo(IngestionStatus.PENDING);
        assertThat(response.dispatched()).isFalse();
        verify(documentRepository, never()).save(any());
        verify(ingestionPipelineService, never()).run(any(), any());
    }

    /**
     * Grooming #26: a {@code FAILED} document/job is reset in place and genuinely re-dispatched,
     * rather than staying permanently stuck - the one short-circuit case that both writes and
     * dispatches.
     */
    @Test
    void resetsAndRedispatchesAFailedDocumentOnRetryUpload() {
        Document existing =
                Document.create(
                        tenantId,
                        knowledgeBase,
                        "notes.txt",
                        "text/plain",
                        IngestionStatus.FAILED,
                        "irrelevant-for-this-test");
        when(documentRepository.findByTenantIdAndKnowledgeBase_IdAndContentHash(
                        any(), any(), any()))
                .thenReturn(Optional.of(existing));
        IngestionJob existingJob = IngestionJob.create(tenantId, existing, IngestionStatus.FAILED);
        existingJob.setErrorDetail("provider unreachable");
        existingJob.setStage("EMBED");
        when(ingestionJobRepository.findByDocument_Id(existing.getId()))
                .thenReturn(Optional.of(existingJob));

        UploadDocumentResponse response =
                service.uploadDocument(tenantId, knowledgeBaseId, plainTextFile("hello world"));

        assertThat(response.status()).isEqualTo(IngestionStatus.PENDING);
        assertThat(response.dispatched()).isTrue();
        assertThat(existing.getStatus()).isEqualTo(IngestionStatus.PENDING);
        assertThat(existingJob.getStatus()).isEqualTo(IngestionStatus.PENDING);
        assertThat(existingJob.getErrorDetail()).isNull();
        assertThat(existingJob.getStage()).isNull();
        verify(ingestionPipelineService)
                .run(existingJob.getId(), "hello world".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void detectsAPdfByItsMagicBytesRegardlessOfFilename() {
        MockMultipartFile pdfNamedTxt =
                new MockMultipartFile(
                        "file",
                        "report.pdf",
                        "application/octet-stream",
                        "%PDF-1.4\n%fake-pdf-body".getBytes(StandardCharsets.ISO_8859_1));

        UploadDocumentResponse response =
                service.uploadDocument(tenantId, knowledgeBaseId, pdfNamedTxt);

        assertThat(response.dispatched()).isTrue();
    }
}
