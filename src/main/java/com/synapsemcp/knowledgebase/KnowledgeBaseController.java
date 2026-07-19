package com.synapsemcp.knowledgebase;

import com.synapsemcp.common.TenantContext;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * rag_plan.md Stage 3. Tenant-key-only. Resources are identified by their own opaque {@code id},
 * not a {@code {tenantId}} path segment (unlike {@code ModelConfigController}), so ownership is
 * enforced inside {@link KnowledgeBaseService} rather than via {@code TenantOwnershipInterceptor}.
 */
@RestController
@RequestMapping("/api/v1/knowledgebase")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeBaseResponse create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        return knowledgeBaseService.createKnowledgeBase(TenantContext.get(), request);
    }

    @GetMapping
    public List<KnowledgeBaseResponse> list() {
        return knowledgeBaseService.listKnowledgeBases(TenantContext.get());
    }

    @PutMapping("/{id}")
    public KnowledgeBaseResponse update(
            @PathVariable UUID id, @Valid @RequestBody UpdateKnowledgeBaseRequest request) {
        return knowledgeBaseService.updateKnowledgeBase(TenantContext.get(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        knowledgeBaseService.deleteKnowledgeBase(TenantContext.get(), id);
    }
}
