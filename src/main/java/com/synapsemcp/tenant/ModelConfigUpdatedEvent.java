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

    // ApplicationEvent implements Serializable by inheritance from java.util.EventObject; this
    // event is only ever dispatched synchronously in-JVM (never actually serialized), but an
    // explicit UID avoids relying on the JVM's structure-derived default (SpotBugs
    // SE_NO_SERIALVERSIONID, `plan.md` §9 2026-07-17).
    private static final long serialVersionUID = 1L;

    private final UUID tenantId;

    public ModelConfigUpdatedEvent(Object source, UUID tenantId) {
        super(source);
        this.tenantId = tenantId;
    }

    public UUID getTenantId() {
        return tenantId;
    }
}
