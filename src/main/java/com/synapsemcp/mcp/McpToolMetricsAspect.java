package com.synapsemcp.mcp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * mcp_plan.md Cross-Cutting Concerns, Grooming #10: Micrometer metrics for MCP tool invocation
 * counts and execution latency, following {@code IngestionMetrics}'s established {@code
 * Counter.builder(...).tag(...).register(meterRegistry)} pattern.
 *
 * <p>A separate {@code @Aspect} from {@link McpToolAccessAspect} rather than folded into it - one
 * aspect per cross-cutting concern, matching this codebase's own convention ({@code
 * McpToolAccessAspect} for access control, {@code com.synapsemcp.common.RateLimitAspect} for rate
 * limiting, this one for observability). Ordered at {@code HIGHEST_PRECEDENCE} so it wraps
 * <b>outside</b> {@code McpToolAccessAspect} (which has no explicit {@code @Order} and so defaults
 * to lowest precedence / innermost) - user confirmed every call attempt should be recorded,
 * including one blocked by tenant-linking or rate limiting, not just calls that reach the real tool
 * logic, so metrics reflect true call volume including abuse/misconfiguration attempts.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class McpToolMetricsAspect {

    private static final String INVOCATIONS_METRIC = "synapsemcp.mcp.tool.invocations";
    private static final String DURATION_METRIC = "synapsemcp.mcp.tool.duration";

    private final MeterRegistry meterRegistry;

    McpToolMetricsAspect(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Around("@annotation(org.springframework.ai.mcp.annotation.McpTool)")
    public Object recordToolMetrics(ProceedingJoinPoint joinPoint) throws Throwable {
        McpTool mcpTool =
                ((MethodSignature) joinPoint.getSignature())
                        .getMethod()
                        .getAnnotation(McpTool.class);
        String toolName = mcpTool.name();
        long startNanos = System.nanoTime();
        String outcome = "success";
        try {
            return joinPoint.proceed();
        } catch (Throwable t) {
            outcome = "error";
            throw t;
        } finally {
            recordInvocation(toolName, outcome);
            recordDuration(toolName, System.nanoTime() - startNanos);
        }
    }

    private void recordInvocation(String toolName, String outcome) {
        Counter.builder(INVOCATIONS_METRIC)
                .tag("tool", toolName)
                .tag("outcome", outcome)
                .description(
                        "MCP tool invocation count, including calls blocked by access control"
                                + " or rate limiting")
                .register(meterRegistry)
                .increment();
    }

    private void recordDuration(String toolName, long elapsedNanos) {
        Timer.builder(DURATION_METRIC)
                .tag("tool", toolName)
                .description("MCP tool execution latency")
                .register(meterRegistry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }
}
