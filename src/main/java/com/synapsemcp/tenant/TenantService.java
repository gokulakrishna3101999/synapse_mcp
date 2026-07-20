package com.synapsemcp.tenant;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.document.DocumentStatusSummary;
import com.synapsemcp.knowledgebase.KnowledgeBaseResponse;
import com.synapsemcp.knowledgebase.KnowledgeBaseService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** rag_plan.md Stage 1: provisions a tenant and its initial (only, for this milestone) API key. */
@Service
public class TenantService {

    private final TenantRepository tenantRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final KnowledgeBaseService knowledgeBaseService;

    TenantService(
            TenantRepository tenantRepository,
            ApiKeyRepository apiKeyRepository,
            KnowledgeBaseService knowledgeBaseService) {
        this.tenantRepository = tenantRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Transactional
    public CreateTenantResponse createTenant(String name) {
        Tenant tenant = tenantRepository.save(new Tenant(name));
        String rawApiKey = ApiKeyGenerator.generate();
        apiKeyRepository.save(new ApiKey(tenant, ApiKeyHasher.sha256Hex(rawApiKey)));
        return new CreateTenantResponse(tenant.getId(), tenant.getName(), rawApiKey);
    }

    /**
     * mcp_plan.md Stage 2 {@code get_tenant} tool - reuses {@link
     * KnowledgeBaseService#listKnowledgeBases} rather than adding a second aggregation query path,
     * summing each knowledge_base's own {@code documentStatusSummary} into one tenant-wide rollup.
     */
    @Transactional(readOnly = true)
    public TenantDetailResponse getTenantDetail(UUID tenantId) {
        Tenant tenant =
                tenantRepository
                        .findById(tenantId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.NOT_FOUND,
                                                "Not Found",
                                                "tenant not found"));
        List<KnowledgeBaseResponse> knowledgeBases =
                knowledgeBaseService.listKnowledgeBases(tenantId);
        DocumentStatusSummary summary = DocumentStatusSummary.EMPTY;
        for (KnowledgeBaseResponse knowledgeBase : knowledgeBases) {
            summary = summary.plus(knowledgeBase.documentStatusSummary());
        }
        return new TenantDetailResponse(
                tenant.getId(),
                tenant.getName(),
                tenant.getCreatedAt(),
                knowledgeBases.size(),
                summary);
    }
}
