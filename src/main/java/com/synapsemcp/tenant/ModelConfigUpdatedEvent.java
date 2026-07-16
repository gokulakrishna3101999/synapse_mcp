package com.synapsemcp.tenant;

import java.util.UUID;
import org.springframework.context.ApplicationEvent;

/**
 * Published by {@link ModelConfigService} on every create/update; {@code ChatModelFactory}/{@code
 * EmbeddingModelFactory} (Stage 2) listen to evict their per-tenant cache immediately, rather than
 * requiring an app restart to pick up new credentials (`plan.md` §9, 2026-07-13). An event, not a
 * direct cross-package method call, to keep {@code com.synapsemcp.tenant} from depending on the
 * (future) embedding/chat factory package - the wrong dependency direction.
 */
public class ModelConfigUpdatedEvent extends ApplicationEvent {

    private final UUID tenantId;

    public ModelConfigUpdatedEvent(Object source, UUID tenantId) {
        super(source);
        this.tenantId = tenantId;
    }

    public UUID getTenantId() {
        return tenantId;
    }
}
