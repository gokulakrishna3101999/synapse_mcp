package com.synapsemcp.ingestion;

import com.synapsemcp.common.ApiException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * rag_plan.md Stage 5: backs both {@code GET /api/v1/jobs/{jobId}} and {@code GET
 * /api/v1/documents/{documentId}/status} - the latter "maps to job status" (rag_plan.md's own
 * words), i.e. the same {@link JobStatusResponse} shape, just looked up by the document's own id
 * instead of its job id ({@code ingestion_jobs.document_id} is {@code UNIQUE} - Grooming #6b - so
 * there is always exactly one job per document to map to). A job/document belonging to a different
 * tenant is treated identically to a nonexistent id - {@code 404}, same convention as {@code
 * KnowledgeBaseService}/{@code DocumentUploadService}'s ownership checks.
 */
@Service
public class IngestionJobService {

    private final IngestionJobRepository ingestionJobRepository;

    public IngestionJobService(IngestionJobRepository ingestionJobRepository) {
        this.ingestionJobRepository = ingestionJobRepository;
    }

    public JobStatusResponse getJobStatus(UUID tenantId, UUID jobId) {
        IngestionJob job =
                requireOwnedJob(
                        ingestionJobRepository.findById(jobId),
                        tenantId,
                        "ingestion job not found");
        return toResponse(job);
    }

    public JobStatusResponse getStatusByDocumentId(UUID tenantId, UUID documentId) {
        IngestionJob job =
                requireOwnedJob(
                        ingestionJobRepository.findByDocument_Id(documentId),
                        tenantId,
                        "document not found");
        return toResponse(job);
    }

    private static IngestionJob requireOwnedJob(
            Optional<IngestionJob> maybeJob, UUID tenantId, String notFoundMessage) {
        return maybeJob.filter(j -> j.getTenantId().equals(tenantId))
                .orElseThrow(
                        () -> new ApiException(HttpStatus.NOT_FOUND, "Not Found", notFoundMessage));
    }

    private static JobStatusResponse toResponse(IngestionJob job) {
        return new JobStatusResponse(
                job.getId(),
                job.getDocumentId(),
                job.getStatus(),
                job.getStage(),
                job.getErrorDetail(),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }
}
