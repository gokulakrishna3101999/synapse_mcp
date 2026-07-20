package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.IngestionStatus;
import com.synapsemcp.common.TenantContext;
import com.synapsemcp.ingestion.IngestionJobService;
import com.synapsemcp.ingestion.JobStatusResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JobStatusMcpToolTest {

    private final IngestionJobService ingestionJobService = mock(IngestionJobService.class);
    private final JobStatusMcpTool tool = new JobStatusMcpTool(ingestionJobService);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void parsesTheIdAndDelegatesToIngestionJobService() {
        UUID tenantId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        TenantContext.set(tenantId);
        JobStatusResponse expected =
                new JobStatusResponse(
                        jobId,
                        UUID.randomUUID(),
                        IngestionStatus.READY,
                        null,
                        null,
                        Instant.now(),
                        Instant.now());
        when(ingestionJobService.getJobStatus(tenantId, jobId)).thenReturn(expected);

        JobStatusResponse response = tool.jobStatus(jobId.toString());

        assertThat(response).isEqualTo(expected);
    }

    @Test
    void throwsAClientSafeErrorForAMalformedJobId() {
        TenantContext.set(UUID.randomUUID());

        assertThatThrownBy(() -> tool.jobStatus("not-a-uuid")).isInstanceOf(ApiException.class);
    }
}
