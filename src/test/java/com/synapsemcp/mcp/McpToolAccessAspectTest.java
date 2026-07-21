package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synapsemcp.common.TenantContext;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class McpToolAccessAspectTest {

    private final McpToolAccessAspect aspect = new McpToolAccessAspect();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @McpTool(name = "create_tenant", description = "test")
    void createTenantStub() {}

    @McpTool(name = "switch_tenant", description = "test")
    void switchTenantStub() {}

    @McpTool(name = "search", description = "test")
    void otherToolStub() {}

    @Test
    void blocksAnyToolExceptCreateTenantWhenUnlinked() throws Throwable {
        authenticateAs(null);
        ProceedingJoinPoint joinPoint = joinPointFor("otherToolStub");

        assertThatThrownBy(() -> aspect.gateToolAccess(joinPoint))
                .isInstanceOf(McpToolAccessDeniedException.class)
                .hasMessageContaining("create_tenant");
    }

    @Test
    void allowsCreateTenantEvenWhenUnlinked() throws Throwable {
        authenticateAs(null);
        ProceedingJoinPoint joinPoint = joinPointFor("createTenantStub");
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.gateToolAccess(joinPoint);

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void allowsSwitchTenantEvenWhenUnlinked() throws Throwable {
        authenticateAs(null);
        ProceedingJoinPoint joinPoint = joinPointFor("switchTenantStub");
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.gateToolAccess(joinPoint);

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void injectsTenantContextForTheDurationOfTheCallAndClearsItAfterward() throws Throwable {
        UUID tenantId = UUID.randomUUID();
        authenticateAs(tenantId);
        ProceedingJoinPoint joinPoint = joinPointFor("otherToolStub");
        UUID[] seenDuringProceed = new UUID[1];
        when(joinPoint.proceed())
                .thenAnswer(
                        invocation -> {
                            seenDuringProceed[0] = TenantContext.get();
                            return "ok";
                        });

        aspect.gateToolAccess(joinPoint);

        assertThat(seenDuringProceed[0]).isEqualTo(tenantId);
        assertThat(TenantContext.get()).isNull();
    }

    private void authenticateAs(UUID tenantId) {
        McpUserPrincipal principal =
                new McpUserPrincipal(UUID.randomUUID(), "alice", "hash", tenantId, null);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities()));
    }

    private ProceedingJoinPoint joinPointFor(String methodName) throws NoSuchMethodException {
        var method = McpToolAccessAspectTest.class.getDeclaredMethod(methodName);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        return joinPoint;
    }
}
