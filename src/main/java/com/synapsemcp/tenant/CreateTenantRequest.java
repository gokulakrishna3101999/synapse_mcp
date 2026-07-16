package com.synapsemcp.tenant;

import jakarta.validation.constraints.NotBlank;

public record CreateTenantRequest(@NotBlank String name) {}
