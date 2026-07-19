package com.synapsemcp.ingestion;

import com.synapsemcp.common.TenantContext;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** rag_plan.md Stage 5: tenant-scoped ingestion job status polling. */
@RestController
@RequestMapping("/api/v1/jobs")
public class IngestionJobController {

    private final IngestionJobService ingestionJobService;

    public IngestionJobController(IngestionJobService ingestionJobService) {
        this.ingestionJobService = ingestionJobService;
    }

    @GetMapping("/{jobId}")
    public JobStatusResponse getJobStatus(@PathVariable UUID jobId) {
        return ingestionJobService.getJobStatus(TenantContext.get(), jobId);
    }
}
