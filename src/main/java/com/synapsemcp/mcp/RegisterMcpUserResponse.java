package com.synapsemcp.mcp;

import java.util.UUID;

/**
 * {@code tenantId} is {@code null} when registered without an {@code apiKey} (Scenario A) - the
 * account is linked later via the {@code create_tenant} MCP tool. Never echoes the password.
 */
public record RegisterMcpUserResponse(UUID id, String username, UUID tenantId) {}
