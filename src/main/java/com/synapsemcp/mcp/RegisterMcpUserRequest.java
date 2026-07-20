package com.synapsemcp.mcp;

import jakarta.validation.constraints.NotBlank;

/**
 * mcp_plan.md Stage 1: {@code apiKey} is optional - present only for a human who already has a REST
 * tenant and wants to link this new MCP account to it (Grooming #14, Scenario B); absent for a
 * brand-new user who will link via the {@code create_tenant} MCP tool instead (Scenario A).
 */
public record RegisterMcpUserRequest(
        @NotBlank String username, @NotBlank String password, String apiKey) {}
