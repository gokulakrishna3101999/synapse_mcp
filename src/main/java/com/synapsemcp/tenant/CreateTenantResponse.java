package com.synapsemcp.tenant;

import java.util.UUID;

/** {@code apiKey} is the raw, unhashed key - shown exactly once, never retrievable again. */
public record CreateTenantResponse(UUID tenantId, String name, String apiKey) {}
