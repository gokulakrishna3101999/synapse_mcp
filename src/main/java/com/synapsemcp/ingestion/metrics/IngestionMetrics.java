package com.synapsemcp.ingestion.metrics;

import com.synapsemcp.common.IngestionStatus;
import com.synapsemcp.ingestion.IngestionJobRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * rag_plan.md Stage 5 / Grooming #8: basic ingestion metrics, exposed via {@code /actuator/metrics}
 * (Micrometer gauges/counters - user confirmed, `plan.md` §9 2026-07-17). System-wide only, not
 * per-tenant - this app has no admin tier to scope a per-tenant view to, and the plan only asks for
 * "basic" metrics this milestone; full stage-duration histograms are explicitly deferred to Phase
 * 3.
 *
 * <p>"Jobs by state" is a live snapshot - a {@link Gauge} backed by a {@code COUNT(*) ... WHERE
 * status = ?} query per state, evaluated whenever a scrape reads it, so it always reflects current
 * {@code ingestion_jobs} contents (including jobs the reconciliation cron repairs back to {@code
 * READY}). "Failure counts by stage and error type" is instead a cumulative {@link Counter},
 * incremented once by {@code IngestionPipelineService} at the moment a job fails - a snapshot gauge
 * can't answer "how many failures of this kind have there ever been", since a repaired or retried
 * job stops being {@code FAILED} in the DB.
 */
@Component
public class IngestionMetrics {

    private static final String JOBS_METRIC = "synapsemcp.ingestion.jobs";
    private static final String FAILURES_METRIC = "synapsemcp.ingestion.failures";

    private final MeterRegistry meterRegistry;

    IngestionMetrics(MeterRegistry meterRegistry, IngestionJobRepository ingestionJobRepository) {
        this.meterRegistry = meterRegistry;
        for (IngestionStatus status : IngestionStatus.values()) {
            Gauge.builder(JOBS_METRIC, ingestionJobRepository, repo -> repo.countByStatus(status))
                    .tag("state", status.name())
                    .description("Current count of ingestion_jobs rows in this state")
                    .register(meterRegistry);
        }
    }

    /** Called by {@code IngestionPipelineService} at the moment a job transitions to FAILED. */
    public void recordFailure(String stage, String errorType) {
        Counter.builder(FAILURES_METRIC)
                .tag("stage", stage == null ? "unknown" : stage)
                .tag("error_type", errorType)
                .description("Cumulative count of ingestion job failures")
                .register(meterRegistry)
                .increment();
    }
}
