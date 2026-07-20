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

class GetDocumentStatusMcpToolTest {

    private final IngestionJobService ingestionJobService = mock(IngestionJobService.class);
    private final GetDocumentStatusMcpTool tool = new GetDocumentStatusMcpTool(ingestionJobService);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void parsesTheIdAndDelegatesToIngestionJobService() {
        UUID tenantId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        TenantContext.set(tenantId);
        JobStatusResponse expected =
                new JobStatusResponse(
                        UUID.randomUUID(),
                        documentId,
                        IngestionStatus.FAILED,
                        null,
                        "extraction failed",
                        Instant.now(),
                        Instant.now());
        when(ingestionJobService.getStatusByDocumentId(tenantId, documentId)).thenReturn(expected);

        JobStatusResponse response = tool.getDocumentStatus(documentId.toString());

        assertThat(response).isEqualTo(expected);
    }

    @Test
    void throwsAClientSafeErrorForAMalformedDocumentId() {
        TenantContext.set(UUID.randomUUID());

        assertThatThrownBy(() -> tool.getDocumentStatus("not-a-uuid"))
                .isInstanceOf(ApiException.class);
    }
}
