package com.synapsemcp.config;

import com.synapsemcp.common.ContextPropagatingTaskDecorator;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The {@code ingestionExecutor} bean Stage 4's {@code @Async("ingestionExecutor")} dispatch fires
 * onto (rag_plan.md Stage 4/5). Sizing is Grooming #9's already-decided values (core 4 / max 8 /
 * queue 100 / {@code CallerRunsPolicy}) - documented under Stage 5 since that's where the actual
 * pipeline work runs, but the executor bean itself has to exist for Stage 4's dispatch call to
 * compile and run at all, so it's created here rather than deferred. {@code CallerRunsPolicy} - not
 * a bounded-queue rejection - means a saturated pipeline (all 8 threads busy, 100 queued) makes the
 * *submitting* HTTP thread run the task inline instead of dropping or throwing; slower than normal
 * under load, but never silently loses a queued upload.
 *
 * <p>{@link ContextPropagatingTaskDecorator} is wired in unconditionally - without it, {@code
 * TenantContext} and the correlation-ID MDC entry would not cross into the worker thread, which
 * `plan.md`'s Stage 0 Javadoc already flags as the single highest-blast-radius bug this project can
 * have (cross-tenant data leakage) - never construct a {@code ThreadPoolTaskExecutor} for
 * {@code @Async} work in this codebase without it.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final int CORE_POOL_SIZE = 4;
    private static final int MAX_POOL_SIZE = 8;
    private static final int QUEUE_CAPACITY = 100;

    @Bean(name = "ingestionExecutor")
    public ThreadPoolTaskExecutor ingestionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("ingestion-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.initialize();
        return executor;
    }
}
