package com.synapsemcp.mcp;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * mcp_plan.md Stage 1. Open/unauthenticated by design, mirroring {@code TenantController}'s own
 * REST tenant-creation endpoint - guarded only by {@link McpUserRegistrationRateLimitFilter}
 * (5/hour/IP, Grooming #15).
 */
@RestController
@RequestMapping("/api/v1/mcp-users")
public class McpUserRegistrationController {

    private final McpUserRegistrationService registrationService;

    public McpUserRegistrationController(McpUserRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterMcpUserResponse register(@Valid @RequestBody RegisterMcpUserRequest request) {
        return registrationService.register(request);
    }
}
