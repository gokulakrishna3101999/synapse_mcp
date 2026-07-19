package com.synapsemcp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} methods - first user is {@code
 * com.synapsemcp.ingestion.reconcile.IngestionReconciliationJob} (rag_plan.md Stage 5d, Grooming
 * #7). A dedicated config class, matching this codebase's one-concern-per-{@code @Configuration}
 * convention (e.g. {@link AsyncConfig} for {@code @EnableAsync}), rather than bolting the
 * annotation onto an unrelated class.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
