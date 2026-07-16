package com.synapsemcp.config;

import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Adds the pgvector HNSW ANN indexes and the {@code CHECK} constraints that plain JPA/{@code
 * ddl-auto} can't express (rag_plan.md Stage 0.5) - runs once Hibernate's own schema step has
 * completed (normal {@code ApplicationRunner} timing, after all beans including {@code
 * EntityManagerFactory} exist). Runs unconditionally on every startup: every statement is
 * idempotent, so after the first real run this is a fast no-op, and it self-heals if an index or
 * constraint is ever manually dropped.
 *
 * <p>Gated on its own {@code synapsemcp.ann-bootstrap.enabled} flag - deliberately <b>not</b> the
 * same {@code synapsemcp.bootstrap.enabled} flag {@link DatabaseBootstrapRunner} uses. That runner
 * needs elevated maintenance-database credentials to create the role/database itself, so it's
 * rightly {@code local}/{@code dev}-only; this one only ever touches an already-existing database
 * over the app's normal datasource, so it has no such restriction and also runs in {@code test} -
 * every {@code *IntegrationTest} needs the same HNSW indexes and {@code CHECK} constraints the app
 * itself has, or tests exercising ANN search or DB-level provider validation would silently pass
 * against a schema that doesn't match local/dev/prod. Bundling both runners under one flag was an
 * oversight found by directly inspecting the `test`-profile schema: {@code synapsemcp_test} had
 * zero HNSW indexes and no {@code model_configs} `CHECK` constraints at all (`plan.md` §9,
 * 2026-07-16). Still {@code false} in {@code prod} (Grooming #18): indexes/constraints must already
 * be in place before deploy there.
 *
 * <p>Only adds {@code CHECK} constraints for {@code model_configs.chat_provider}/{@code
 * embedding_provider} (plain {@code String} columns) - {@code documents.status}/{@code
 * ingestion_jobs.status} do <b>not</b> need one added here, since
 * {@code @Enumerated(EnumType.STRING)} on {@link com.synapsemcp.common.IngestionStatus} makes
 * Hibernate's own {@code ddl-auto} generate an equivalent {@code CHECK} constraint automatically -
 * verified empirically (Hibernate 7.4.1): adding a second, identical one here just produced a
 * redundant duplicate constraint (harmless but sloppy) rather than anything {@code ddl-auto}
 * genuinely can't do.
 */
@Component
@ConditionalOnProperty(prefix = "synapsemcp.ann-bootstrap", name = "enabled", havingValue = "true")
public class AnnIndexBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AnnIndexBootstrapRunner.class);
    private static final String DUPLICATE_OBJECT_SQLSTATE = "42710";
    private static final int[] EMBEDDING_DIMENSIONS = {384, 512, 768, 1024, 1536, 3072};

    /**
     * pgvector's HNSW index caps at 2000 dimensions for the plain {@code vector} type - the 3072
     * column uses {@code halfvec} instead (see {@link
     * com.synapsemcp.rag.chunk.Chunk#getEmbedding3072()}), which needs the matching {@code
     * halfvec_cosine_ops} operator class, not {@code vector_cosine_ops}.
     */
    private static final int HALF_PRECISION_DIMENSION = 3072;

    private final JdbcTemplate jdbcTemplate;

    public AnnIndexBootstrapRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (int dimension : EMBEDDING_DIMENSIONS) {
            String opClass =
                    dimension == HALF_PRECISION_DIMENSION
                            ? "halfvec_cosine_ops"
                            : "vector_cosine_ops";
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_chunks_emb_"
                            + dimension
                            + " ON chunks USING hnsw (embedding_"
                            + dimension
                            + " "
                            + opClass
                            + ")");
        }

        addCheckConstraintIfMissing(
                "model_configs",
                "chk_model_configs_chat_provider",
                "chat_provider IN ('openai','anthropic','ollama','google-genai')");
        addCheckConstraintIfMissing(
                "model_configs",
                "chk_model_configs_embedding_provider",
                "embedding_provider IN ('openai','ollama','google-genai')");

        log.info("ANN indexes and CHECK constraints verified");
    }

    private void addCheckConstraintIfMissing(
            String table, String constraintName, String checkExpression) {
        try {
            jdbcTemplate.execute(
                    "ALTER TABLE "
                            + table
                            + " ADD CONSTRAINT "
                            + constraintName
                            + " CHECK ("
                            + checkExpression
                            + ")");
        } catch (DataAccessException e) {
            if (!isDuplicateObject(e)) {
                throw e;
            }
        }
    }

    private boolean isDuplicateObject(DataAccessException e) {
        Throwable mostSpecificCause = e.getMostSpecificCause();
        return mostSpecificCause instanceof SQLException sqlException
                && DUPLICATE_OBJECT_SQLSTATE.equals(sqlException.getSQLState());
    }
}
