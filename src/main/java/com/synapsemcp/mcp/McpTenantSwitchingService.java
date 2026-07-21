package com.synapsemcp.mcp;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.tenant.ApiKeyHasher;
import com.synapsemcp.tenant.ApiKeyRepository;
import com.synapsemcp.tenant.Tenant;
import com.synapsemcp.tenant.TenantDetailResponse;
import com.synapsemcp.tenant.TenantRepository;
import com.synapsemcp.tenant.TenantService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User-requested (2026-07-21): switches an already-registered MCP account to a different,
 * already-existing tenant, proven by presenting that tenant's own API key. Re-proving ownership via
 * the API key on every switch is symmetric with how REST access already works (whichever key you
 * present is what you can access) - the account doesn't need to have ever touched this tenant
 * before; the API key alone is the proof.
 *
 * <p>Deliberately distinct from {@link McpTenantLinkingService} ({@code create_tenant}'s sole
 * purpose remains creating a brand-new tenant from scratch, user-confirmed, unchanged): this
 * service only ever attaches to an <em>existing</em> tenant, never creates one.
 *
 * <p>mcp_plan.md Grooming #26 (user-requested, 2026-07-21): "only 5 tenants per mcp_user including
 * create and switch tenants" - enforced here via {@link McpUserTenantLinkRepository}, an append-
 * only per-account history of every distinct tenant ever touched (by {@code create_tenant} or
 * {@code switch_tenant}). Switching to a tenant already in that history is always free (it isn't
 * new); switching to a brand-new one is only allowed while the account's distinct-tenant count is
 * still under {@link #MAX_TENANTS_PER_MCP_USER}. The account row is pessimistically locked first
 * (mirroring {@code TenantRepository.lockById}'s own count-check race guard) so two concurrent
 * switches to two different new tenants, made when exactly one slot remains, can't both pass the
 * count check and push the account over the cap.
 */
@Service
public class McpTenantSwitchingService {

    static final int MAX_TENANTS_PER_MCP_USER = 5;

    private final ApiKeyRepository apiKeyRepository;
    private final TenantRepository tenantRepository;
    private final McpUserRepository mcpUserRepository;
    private final McpUserTenantLinkRepository linkRepository;
    private final TenantService tenantService;

    McpTenantSwitchingService(
            ApiKeyRepository apiKeyRepository,
            TenantRepository tenantRepository,
            McpUserRepository mcpUserRepository,
            McpUserTenantLinkRepository linkRepository,
            TenantService tenantService) {
        this.apiKeyRepository = apiKeyRepository;
        this.tenantRepository = tenantRepository;
        this.mcpUserRepository = mcpUserRepository;
        this.linkRepository = linkRepository;
        this.tenantService = tenantService;
    }

    @Transactional
    public TenantDetailResponse switchTenant(UUID mcpUserId, UUID tenantId, String apiKey) {
        UUID resolvedTenantId =
                apiKeyRepository
                        .findTenantIdByKeyHash(ApiKeyHasher.sha256Hex(apiKey))
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.UNAUTHORIZED,
                                                "Unauthorized",
                                                "invalid API key"));
        if (!resolvedTenantId.equals(tenantId)) {
            // Deliberately not "tenant not found" (which would imply the id itself is somehow
            // wrong) - the id is fine, the key just doesn't prove ownership of it. No enumeration
            // risk in saying so plainly: the caller already holds a real, valid API key for *some*
            // tenant by this point, so this reveals nothing they couldn't already infer.
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "Unauthorized",
                    "the provided apiKey does not belong to the given tenantId");
        }

        mcpUserRepository.lockById(mcpUserId);
        if (!linkRepository.existsByMcpUserIdAndTenantId(mcpUserId, tenantId)) {
            if (linkRepository.countByMcpUserId(mcpUserId) >= MAX_TENANTS_PER_MCP_USER) {
                throw new ApiException(
                        HttpStatus.FORBIDDEN,
                        "Forbidden",
                        "this account has already reached the maximum of "
                                + MAX_TENANTS_PER_MCP_USER
                                + " tenants");
            }
            linkRepository.save(new McpUserTenantLink(mcpUserId, tenantId));
        }

        Tenant tenant = tenantRepository.getReferenceById(tenantId);
        mcpUserRepository.switchTenant(mcpUserId, tenant);
        // User-requested (2026-07-22): a knowledge base "active" for the tenant this account is
        // switching away from is never meaningful for the tenant it's switching to (even when
        // switching back to a tenant visited before - this account has no per-tenant history of
        // which knowledge base was active there, only the single current pointer, matching this
        // whole feature's own deliberately simple, no-history design).
        mcpUserRepository.clearActiveKnowledgeBase(mcpUserId);
        return tenantService.getTenantDetail(tenantId);
    }
}
