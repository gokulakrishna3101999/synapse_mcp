package com.synapsemcp.common;

import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * Propagates {@link TenantContext} and the SLF4J MDC (correlation ID) from the submitting thread
 * into an {@code @Async} worker thread - a plain {@link ThreadLocal} does not cross a thread-pool
 * boundary on its own, and losing either one here is the single easiest-to-miss,
 * highest-blast-radius bug in the ingestion pipeline (rag_plan.md Stage 0): a lost tenant context
 * risks cross-tenant data leakage, and a lost correlation ID makes async logs untraceable. Wire
 * this into any {@code ThreadPoolTaskExecutor} used for {@code @Async} work via {@code
 * setTaskDecorator(new ContextPropagatingTaskDecorator())}.
 */
public class ContextPropagatingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        UUID tenantId = TenantContext.get();
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (tenantId != null) {
                    TenantContext.set(tenantId);
                }
                if (mdcContext != null) {
                    MDC.setContextMap(mdcContext);
                }
                runnable.run();
            } finally {
                TenantContext.clear();
                MDC.clear();
            }
        };
    }
}
