package com.synapsemcp.common;

import java.util.UUID;

/**
 * Request-scoped current-tenant holder (rag_plan.md Stage 0). Populated by the API-key auth filter
 * on every authenticated request and read by services/repositories to scope queries. Must be
 * propagated into {@code @Async} worker threads explicitly - see {@link
 * ContextPropagatingTaskDecorator} - since a plain {@link ThreadLocal} does not cross thread-pool
 * boundaries on its own.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static UUID get() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
