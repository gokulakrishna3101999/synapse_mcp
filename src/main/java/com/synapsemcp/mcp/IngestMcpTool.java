package com.synapsemcp.mcp;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.TenantContext;
import com.synapsemcp.document.DocumentUploadService;
import com.synapsemcp.document.UploadDocumentResponse;
import java.util.Base64;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * mcp_plan.md Stage 2 {@code ingest} tool. Accepts exactly one of two input shapes - file bytes
 * ({@code filename} + {@code contentBase64}, routed through {@link
 * DocumentUploadService#uploadBytes}) or raw text ({@code text}, routed through {@link
 * DocumentUploadService#uploadRawText}) - never a URL/external-source reference (rag_plan.md §2
 * Milestone 7 external connectors remain SPI-only, unimplemented).
 *
 * <p>Enforces its own size cap on the decoded byte length against the same {@code
 * spring.servlet.multipart.max-file-size} value REST's multipart resolver uses (Grooming #19) -
 * there is no multipart resolver on this transport to do it automatically, and {@code
 * RequestBodySizeLimitFilter} exempts {@code /mcp} for the same reason.
 */
@Component
public class IngestMcpTool {

    private final DocumentUploadService documentUploadService;
    private final long maxContentBytes;

    public IngestMcpTool(
            DocumentUploadService documentUploadService,
            @Value("${spring.servlet.multipart.max-file-size}") String maxFileSize) {
        this.documentUploadService = documentUploadService;
        this.maxContentBytes = DataSize.parse(maxFileSize).toBytes();
    }

    @McpTool(
            name = "ingest",
            description =
                    "Uploads a document into a knowledge base for ingestion (extraction, "
                            + "chunking, embedding, indexing). Provide either filename + "
                            + "contentBase64 (file bytes) or text (raw text), not both. This tool "
                            + "returns immediately with a Job ID. You MUST use the job_status "
                            + "tool every 5 seconds until the job completes.")
    public UploadDocumentResponse ingest(
            @McpToolParam(description = "Id of the knowledge base to ingest into")
                    String knowledgeBaseId,
            @McpToolParam(
                            description = "Filename (used with contentBase64; ignored for text)",
                            required = false)
                    String filename,
            @McpToolParam(
                            description =
                                    "Base64-encoded file bytes - provide this or text, not both",
                            required = false)
                    String contentBase64,
            @McpToolParam(
                            description =
                                    "Raw text content - provide this or contentBase64, not both",
                            required = false)
                    String text) {
        UUID id = McpToolInputs.parseUuid(knowledgeBaseId, "knowledgeBaseId");
        boolean hasBytes = contentBase64 != null && !contentBase64.isBlank();
        boolean hasText = text != null && !text.isBlank();
        if (hasBytes == hasText) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Bad Request",
                    "provide exactly one of contentBase64 or text");
        }

        UUID tenantId = TenantContext.get();
        if (hasBytes) {
            byte[] content = decodeBase64(contentBase64);
            if (content.length > maxContentBytes) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "Bad Request",
                        "content exceeds maximum allowed size of " + maxContentBytes + " bytes");
            }
            return documentUploadService.uploadBytes(tenantId, id, content, filename);
        }
        return documentUploadService.uploadRawText(tenantId, id, text, filename);
    }

    private static byte[] decodeBase64(String contentBase64) {
        try {
            return Base64.getDecoder().decode(contentBase64);
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "Bad Request", "contentBase64 is not valid base64");
        }
    }
}
