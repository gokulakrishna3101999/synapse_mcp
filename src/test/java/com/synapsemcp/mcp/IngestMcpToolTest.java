package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.IngestionStatus;
import com.synapsemcp.common.TenantContext;
import com.synapsemcp.document.DocumentUploadService;
import com.synapsemcp.document.UploadDocumentResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IngestMcpToolTest {

    private static final long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024;

    private final DocumentUploadService documentUploadService = mock(DocumentUploadService.class);
    private final IngestMcpTool tool = new IngestMcpTool(documentUploadService, "20MB");

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void routesTheFileBytesShapeToUploadBytes() {
        UUID tenantId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        TenantContext.set(tenantId);
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        String base64 = Base64.getEncoder().encodeToString(content);
        UploadDocumentResponse expected =
                new UploadDocumentResponse(
                        UUID.randomUUID(), UUID.randomUUID(), IngestionStatus.PENDING, true);
        when(documentUploadService.uploadBytes(
                        eq(tenantId), eq(kbId), eq(content), eq("notes.txt")))
                .thenReturn(expected);

        UploadDocumentResponse response = tool.ingest(kbId.toString(), "notes.txt", base64, null);

        assertThat(response).isEqualTo(expected);
    }

    @Test
    void routesTheRawTextShapeToUploadRawText() {
        UUID tenantId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        TenantContext.set(tenantId);
        UploadDocumentResponse expected =
                new UploadDocumentResponse(
                        UUID.randomUUID(), UUID.randomUUID(), IngestionStatus.PENDING, true);
        when(documentUploadService.uploadRawText(
                        eq(tenantId), eq(kbId), eq("some raw text"), isNull()))
                .thenReturn(expected);

        UploadDocumentResponse response = tool.ingest(kbId.toString(), null, null, "some raw text");

        assertThat(response).isEqualTo(expected);
    }

    @Test
    void throwsAClientSafeErrorWhenNeitherContentBase64NorTextIsProvided() {
        TenantContext.set(UUID.randomUUID());

        assertThatThrownBy(() -> tool.ingest(UUID.randomUUID().toString(), "notes.txt", null, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void throwsAClientSafeErrorWhenBothContentBase64AndTextAreProvided() {
        TenantContext.set(UUID.randomUUID());

        assertThatThrownBy(
                        () ->
                                tool.ingest(
                                        UUID.randomUUID().toString(),
                                        "notes.txt",
                                        Base64.getEncoder()
                                                .encodeToString(
                                                        "x".getBytes(StandardCharsets.UTF_8)),
                                        "some text"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void throwsAClientSafeErrorForInvalidBase64() {
        TenantContext.set(UUID.randomUUID());

        assertThatThrownBy(
                        () ->
                                tool.ingest(
                                        UUID.randomUUID().toString(),
                                        "notes.txt",
                                        "not valid base64!!!",
                                        null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void throwsAClientSafeErrorWhenDecodedContentExceedsTheConfiguredMaxFileSize() {
        TenantContext.set(UUID.randomUUID());
        byte[] oversized = new byte[(int) MAX_FILE_SIZE_BYTES + 1];
        String base64 = Base64.getEncoder().encodeToString(oversized);

        assertThatThrownBy(() -> tool.ingest(UUID.randomUUID().toString(), "big.bin", base64, null))
                .isInstanceOf(ApiException.class);

        verify(documentUploadService, org.mockito.Mockito.never())
                .uploadBytes(any(), any(), any(), any());
    }
}
