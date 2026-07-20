package com.synapsemcp.mcp;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.tenant.ApiKeyHasher;
import com.synapsemcp.tenant.ApiKeyRepository;
import com.synapsemcp.tenant.Tenant;
import com.synapsemcp.tenant.TenantRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * mcp_plan.md Stage 1, Grooming #14. Registers a new {@code mcp_users} account, optionally linking
 * it to an existing REST tenant at creation time (Scenario B: a human who already has an {@code
 * api_key}) - safe to link directly here with no race guard, unlike the {@code create_tenant} MCP
 * tool's linking (Grooming #16), since this is a brand-new row no concurrent caller can see yet.
 */
@Service
public class McpUserRegistrationService {

    private final McpUserRepository mcpUserRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;

    McpUserRegistrationService(
            McpUserRepository mcpUserRepository,
            ApiKeyRepository apiKeyRepository,
            TenantRepository tenantRepository,
            PasswordEncoder passwordEncoder) {
        this.mcpUserRepository = mcpUserRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public RegisterMcpUserResponse register(RegisterMcpUserRequest request) {
        McpUser user = new McpUser(request.username(), passwordEncoder.encode(request.password()));
        if (StringUtils.hasText(request.apiKey())) {
            Tenant tenant = resolveTenantFromApiKey(request.apiKey());
            user.linkTenant(tenant);
        }
        McpUser saved = mcpUserRepository.save(user);
        return new RegisterMcpUserResponse(saved.getId(), saved.getUsername(), saved.getTenantId());
    }

    /**
     * User-confirmed choice (asked, not guessed, since Grooming #14 didn't specify this): a
     * supplied but invalid {@code api_key} rejects the whole registration with {@code 422} rather
     * than silently registering an unlinked account - a caller who clearly intended to link
     * shouldn't be left thinking they succeeded when they didn't.
     */
    private Tenant resolveTenantFromApiKey(String apiKey) {
        UUID tenantId =
                apiKeyRepository
                        .findTenantIdByKeyHash(ApiKeyHasher.sha256Hex(apiKey))
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.UNPROCESSABLE_ENTITY,
                                                "Unprocessable Entity",
                                                "invalid api_key"));
        // The id came straight from a repository lookup keyed on this exact id, so the tenant row
        // is guaranteed to exist - this is a defensive check, not a reachable error path.
        return tenantRepository
                .findById(tenantId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "tenant referenced by a valid api_key was not found"));
    }
}
