package com.synapsemcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

class McpToolMetricsAspectTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final McpToolMetricsAspect aspect = new McpToolMetricsAspect(meterRegistry);

    @McpTool(name = "search", description = "test")
    void searchStub() {}

    @Test
    void recordsASuccessfulInvocationAndItsDuration() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPointFor("searchStub");
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.recordToolMetrics(joinPoint);

        assertThat(result).isEqualTo("ok");
        assertThat(
                        meterRegistry
                                .counter(
                                        "synapsemcp.mcp.tool.invocations",
                                        "tool",
                                        "search",
                                        "outcome",
                                        "success")
                                .count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.find("synapsemcp.mcp.tool.duration").tag("tool", "search").timer())
                .isNotNull();
    }

    @Test
    void recordsAnErrorOutcomeWhenTheToolCallThrows() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPointFor("searchStub");
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> aspect.recordToolMetrics(joinPoint))
                .isInstanceOf(IllegalStateException.class);

        assertThat(
                        meterRegistry
                                .counter(
                                        "synapsemcp.mcp.tool.invocations",
                                        "tool",
                                        "search",
                                        "outcome",
                                        "error")
                                .count())
                .isEqualTo(1.0);
    }

    private ProceedingJoinPoint joinPointFor(String methodName) throws NoSuchMethodException {
        var method = McpToolMetricsAspectTest.class.getDeclaredMethod(methodName);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        return joinPoint;
    }
}
