package com.synapsemcp.tenant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** rag_plan.md Stage 1: provisions a tenant and its initial (only, for this milestone) API key. */
@Service
public class TenantService {

    private final TenantRepository tenantRepository;
    private final ApiKeyRepository apiKeyRepository;

    public TenantService(TenantRepository tenantRepository, ApiKeyRepository apiKeyRepository) {
        this.tenantRepository = tenantRepository;
        this.apiKeyRepository = apiKeyRepository;
    }

    @Transactional
    public CreateTenantResponse createTenant(String name) {
        Tenant tenant = tenantRepository.save(new Tenant(name));
        String rawApiKey = ApiKeyGenerator.generate();
        apiKeyRepository.save(new ApiKey(tenant, ApiKeyHasher.sha256Hex(rawApiKey)));
        return new CreateTenantResponse(tenant.getId(), tenant.getName(), rawApiKey);
    }
}
