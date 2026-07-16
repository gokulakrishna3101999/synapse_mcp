package com.synapsemcp.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Guardrail test (rag_plan.md Stage 0): a lost {@link TenantContext} across an {@code @Async}
 * boundary is the single highest-blast-radius bug this project can have (cross-tenant data
 * leakage), so this test must never be weakened or deleted.
 */
class ContextPropagatingTaskDecoratorTest {

    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
        TenantContext.clear();
        MDC.clear();
    }

    @Test
    void propagatesTenantContextAndCorrelationIdIntoWorkerThread() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-123");

        executor = newSingleThreadExecutor();

        AtomicReference<UUID> tenantSeenByWorker = new AtomicReference<>();
        AtomicReference<String> correlationIdSeenByWorker = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        executor.execute(
                () -> {
                    tenantSeenByWorker.set(TenantContext.get());
                    correlationIdSeenByWorker.set(MDC.get(CorrelationIdFilter.MDC_KEY));
                    latch.countDown();
                });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(tenantSeenByWorker.get()).isEqualTo(tenantId);
        assertThat(correlationIdSeenByWorker.get()).isEqualTo("corr-123");
    }

    @Test
    void clearsContextFromWorkerThreadAfterTaskCompletes() throws InterruptedException {
        TenantContext.set(UUID.randomUUID());
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-456");
        executor = newSingleThreadExecutor();

        CountDownLatch firstTaskDone = new CountDownLatch(1);
        executor.execute(firstTaskDone::countDown);
        assertThat(firstTaskDone.await(5, TimeUnit.SECONDS)).isTrue();

        // Clear the submitting thread's context so a leaked (not re-propagated) value would be
        // detected.
        TenantContext.clear();
        MDC.clear();

        AtomicReference<UUID> tenantSeenBySecondTask = new AtomicReference<>();
        AtomicReference<String> correlationIdSeenBySecondTask = new AtomicReference<>();
        CountDownLatch secondTaskDone = new CountDownLatch(1);

        // Same pooled thread as the first task (single-thread executor) - if the decorator didn't
        // clear
        // the worker thread's ThreadLocals after task 1, this would see task 1's stale values.
        executor.execute(
                () -> {
                    tenantSeenBySecondTask.set(TenantContext.get());
                    correlationIdSeenBySecondTask.set(MDC.get(CorrelationIdFilter.MDC_KEY));
                    secondTaskDone.countDown();
                });

        assertThat(secondTaskDone.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(tenantSeenBySecondTask.get()).isNull();
        assertThat(correlationIdSeenBySecondTask.get()).isNull();
    }

    private ThreadPoolTaskExecutor newSingleThreadExecutor() {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(1);
        taskExecutor.setMaxPoolSize(1);
        taskExecutor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        taskExecutor.initialize();
        return taskExecutor;
    }
}
