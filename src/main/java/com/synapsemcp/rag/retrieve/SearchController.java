package com.synapsemcp.rag.retrieve;

import com.synapsemcp.common.TenantContext;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * rag_plan.md Stage 6a. Tenant-key-only, same ownership pattern as {@link
 * com.synapsemcp.document.DocumentController} - the knowledge_base is identified by its own opaque
 * {@code id}, ownership enforced inside {@link HybridRetrievalService} (404 on mismatch), not a
 * path-segment-matching interceptor.
 */
@RestController
@RequestMapping("/api/v1/knowledgebase/{knowledgebaseId}/search")
public class SearchController {

    private final HybridRetrievalService hybridRetrievalService;

    public SearchController(HybridRetrievalService hybridRetrievalService) {
        this.hybridRetrievalService = hybridRetrievalService;
    }

    @PostMapping
    public List<SearchResultChunk> search(
            @PathVariable UUID knowledgebaseId, @Valid @RequestBody SearchRequest request) {
        return hybridRetrievalService.search(TenantContext.get(), knowledgebaseId, request);
    }
}
