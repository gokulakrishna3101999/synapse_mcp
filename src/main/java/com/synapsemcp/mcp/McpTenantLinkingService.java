package com.synapsemcp.mcp;

import com.synapsemcp.tenant.CreateTenantResponse;
import com.synapsemcp.tenant.Tenant;
import com.synapsemcp.tenant.TenantRepository;
import com.synapsemcp.tenant.TenantService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * mcp_plan.md Grooming #16: tenant provisioning, API-key issuance, and the atomic conditional
 * {@code mcp_users} link all run inside <b>one</b> {@code @Transactional} boundary. {@link
 * TenantService#createTenant} joins this same transaction (default {@code REQUIRED} propagation,
 * since both are Spring-managed beans) rather than committing independently - if the conditional
 * link loses a race (another concurrent {@code create_tenant} call already linked this account),
 * throwing here rolls the whole transaction back, undoing the tenant/API-key inserts too. This is a
 * plain transactional rollback, not a separate compensating delete - no standalone tenant-deletion
 * capability exists or is needed anywhere else in the system for this path.
 */
@Service
public class McpTenantLinkingService {

    private final TenantService tenantService;
    private final TenantRepository tenantRepository;
    private final McpUserRepository mcpUserRepository;
    private final McpUserTenantLinkRepository linkRepository;

    McpTenantLinkingService(
            TenantService tenantService,
            TenantRepository tenantRepository,
            McpUserRepository mcpUserRepository,
            McpUserTenantLinkRepository linkRepository) {
        this.tenantService = tenantService;
        this.tenantRepository = tenantRepository;
        this.mcpUserRepository = mcpUserRepository;
        this.linkRepository = linkRepository;
    }

    @Transactional
    public CreateTenantResponse createAndLinkTenant(UUID mcpUserId, String tenantName) {
        CreateTenantResponse response = tenantService.createTenant(tenantName);
        Tenant tenant = tenantRepository.getReferenceById(response.tenantId());
        int updated = mcpUserRepository.linkTenantIfUnlinked(mcpUserId, tenant);
        if (updated == 0) {
            throw new McpToolAccessDeniedException("This account is already linked to a tenant.");
        }
        // Always this account's first entry: linkTenantIfUnlinked's own IS NULL guard means
        // create_tenant can only ever succeed once per account (mcp_plan.md Grooming #26).
        linkRepository.save(new McpUserTenantLink(mcpUserId, response.tenantId()));
        return response;
    }
}
