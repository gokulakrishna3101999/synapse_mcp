package com.synapsemcp.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.IngestionStatus;
import com.synapsemcp.document.Document;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IngestionJobServiceTest {

    private final IngestionJobRepository ingestionJobRepository =
            mock(IngestionJobRepository.class);
    private final IngestionJobService service = new IngestionJobService(ingestionJobRepository);
    private final UUID tenantId = UUID.randomUUID();

    private static IngestionJob newJob(UUID tenantId, UUID jobId, UUID documentId)
            throws Exception {
        Document document =
                Document.create(tenantId, null, "f.txt", "text/plain", IngestionStatus.READY, "h");
        setId(document, documentId);
        IngestionJob job = IngestionJob.create(tenantId, document, IngestionStatus.READY);
        setId(job, jobId);
        return job;
    }

    private static void setId(Object entity, UUID id) throws Exception {
        Field idField = entity.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(entity, id);
    }

    @Test
    void getJobStatusReturnsTheJobWhenOwnedByTheCallingTenant() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        IngestionJob job = newJob(tenantId, jobId, documentId);
        when(ingestionJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        JobStatusResponse response = service.getJobStatus(tenantId, jobId);

        assertThat(response.jobId()).isEqualTo(jobId);
        assertThat(response.documentId()).isEqualTo(documentId);
        assertThat(response.status()).isEqualTo(IngestionStatus.READY);
    }

    @Test
    void getJobStatusReturns404ForAJobOwnedByAnotherTenant() throws Exception {
        UUID jobId = UUID.randomUUID();
        IngestionJob job = newJob(UUID.randomUUID(), jobId, UUID.randomUUID());
        when(ingestionJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.getJobStatus(tenantId, jobId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus().value())
                .isEqualTo(404);
    }

    @Test
    void getJobStatusReturns404ForANonexistentJob() {
        UUID jobId = UUID.randomUUID();
        when(ingestionJobRepository.findById(jobId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getJobStatus(tenantId, jobId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus().value())
                .isEqualTo(404);
    }

    /**
     * rag_plan.md REST API Summary: {@code GET /api/v1/documents/{documentId}/status} - found
     * missing entirely during a completeness audit (`plan.md` §9 2026-07-18); every other endpoint
     * in the plan's own "complete list" was already implemented.
     */
    @Test
    void getStatusByDocumentIdReturnsTheJobMappedToThatDocument() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        IngestionJob job = newJob(tenantId, jobId, documentId);
        when(ingestionJobRepository.findByDocument_Id(documentId)).thenReturn(Optional.of(job));

        JobStatusResponse response = service.getStatusByDocumentId(tenantId, documentId);

        assertThat(response.jobId()).isEqualTo(jobId);
        assertThat(response.documentId()).isEqualTo(documentId);
    }

    @Test
    void getStatusByDocumentIdReturns404ForADocumentOwnedByAnotherTenant() throws Exception {
        UUID documentId = UUID.randomUUID();
        IngestionJob job = newJob(UUID.randomUUID(), UUID.randomUUID(), documentId);
        when(ingestionJobRepository.findByDocument_Id(documentId)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.getStatusByDocumentId(tenantId, documentId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus().value())
                .isEqualTo(404);
    }

    @Test
    void getStatusByDocumentIdReturns404ForANonexistentDocument() {
        UUID documentId = UUID.randomUUID();
        when(ingestionJobRepository.findByDocument_Id(documentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStatusByDocumentId(tenantId, documentId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus().value())
                .isEqualTo(404);
    }
}
