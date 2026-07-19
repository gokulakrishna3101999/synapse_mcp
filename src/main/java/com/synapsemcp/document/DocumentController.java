package com.synapsemcp.document;

import com.synapsemcp.common.TenantContext;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * rag_plan.md Stage 4. Tenant-key-only, same as {@link
 * com.synapsemcp.knowledgebase.KnowledgeBaseController} - ownership of {@code knowledgebaseId} is
 * enforced inside {@link DocumentUploadService}, not a path-segment-matching interceptor.
 */
@RestController
@RequestMapping("/api/v1/knowledgebase/{knowledgebaseId}/documents")
public class DocumentController {

    private final DocumentUploadService documentUploadService;

    public DocumentController(DocumentUploadService documentUploadService) {
        this.documentUploadService = documentUploadService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadDocumentResponse> upload(
            @PathVariable UUID knowledgebaseId, @RequestParam("file") MultipartFile file) {
        UploadDocumentResponse response =
                documentUploadService.uploadDocument(TenantContext.get(), knowledgebaseId, file);
        HttpStatus status = response.dispatched() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }
}
