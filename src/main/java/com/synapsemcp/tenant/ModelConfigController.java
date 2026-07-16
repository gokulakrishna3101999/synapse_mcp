package com.synapsemcp.tenant;

import com.synapsemcp.common.ApiException;
import com.synapsemcp.common.TenantContext;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * rag_plan.md Stage 2. Tenant-key-only, own-tenant-only: the authenticated caller's {@link
 * TenantContext} must match the {@code tenantId} path variable exactly - a valid API key never
 * grants access to another tenant's config, even though every caller here is already authenticated.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/model-config")
public class ModelConfigController {

    private final ModelConfigService modelConfigService;

    public ModelConfigController(ModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    @PutMapping
    public ModelConfigResponse configureModel(
            @PathVariable UUID tenantId, @Valid @RequestBody ConfigureModelRequest request) {
        requireOwnTenant(tenantId);
        return modelConfigService.configureModel(tenantId, request);
    }

    @GetMapping
    public ModelConfigResponse getModelConfig(@PathVariable UUID tenantId) {
        requireOwnTenant(tenantId);
        return modelConfigService.getModelConfig(tenantId);
    }

    private void requireOwnTenant(UUID tenantId) {
        if (!tenantId.equals(TenantContext.get())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "Forbidden",
                    "cannot access another tenant's model config");
        }
    }
}
