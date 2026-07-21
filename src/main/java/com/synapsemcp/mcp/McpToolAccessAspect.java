package com.synapsemcp.mcp;

import com.synapsemcp.common.RateLimitContext;
import com.synapsemcp.common.TenantContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * mcp_plan.md Stage 1, Grooming #13: gates every {@code @McpTool} method. If the authenticated
 * {@code mcp_users} account is not yet linked to a tenant, every tool except {@code create_tenant}
 * and {@code switch_tenant} is blocked; once linked, every call gets {@link TenantContext}
 * populated for its duration - mirroring exactly the {@code set}/{@code clear} discipline {@code
 * ApiKeyAuthenticationFilter} already uses for REST, just at the MCP tool-invocation boundary
 * instead of the servlet filter boundary (MCP tool calls never go through that filter's REST
 * bearer-token check at all - see {@code ApiKeyAuthenticationFilter}'s own {@code /mcp} bypass).
 *
 * <p>{@code switch_tenant} was added to this allow-list alongside {@code create_tenant}
 * (user-requested, 2026-07-21): a brand-new, never-linked account can attach directly to an
 * already-existing tenant it holds the API key for, exactly as readily as it can create a new one -
 * both are legitimate ways to get linked for the first time, and both remain usable afterward too
 * ({@code create_tenant} still only succeeds once ever; {@code switch_tenant} can be called
 * repeatedly to move between tenants).
 */
@Aspect
@Component
public class McpToolAccessAspect {

    static final String CREATE_TENANT_TOOL_NAME = "create_tenant";
    static final String SWITCH_TENANT_TOOL_NAME = "switch_tenant";

    @Around("@annotation(org.springframework.ai.mcp.annotation.McpTool)")
    public Object gateToolAccess(ProceedingJoinPoint joinPoint) throws Throwable {
        McpTool mcpTool =
                ((MethodSignature) joinPoint.getSignature())
                        .getMethod()
                        .getAnnotation(McpTool.class);
        McpUserPrincipal principal = currentPrincipal();

        if (principal.getTenantId() == null
                && !CREATE_TENANT_TOOL_NAME.equals(mcpTool.name())
                && !SWITCH_TENANT_TOOL_NAME.equals(mcpTool.name())) {
            throw new McpToolAccessDeniedException(
                    "This account is not yet linked to a tenant - call create_tenant or"
                            + " switch_tenant first.");
        }

        TenantContext.set(principal.getTenantId());
        try {
            return joinPoint.proceed();
        } finally {
            TenantContext.clear();
            // mcp_plan.md Stage 3: MCP has no servlet filter equivalent to RateLimitHeaderFilter,
            // so this per-tool-call boundary is the only place left to clear RateLimitContext -
            // MCP never reads it (that transport is scoped to structured errors on limit breach
            // only, not a remaining-count header), this purely prevents it leaking across pooled
            // worker threads onto a later, unrelated tool call.
            RateLimitContext.clear();
        }
    }

    private McpUserPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof McpUserPrincipal principal)) {
            throw new IllegalStateException(
                    "an @McpTool method was invoked without an authenticated McpUserPrincipal");
        }
        return principal;
    }
}
