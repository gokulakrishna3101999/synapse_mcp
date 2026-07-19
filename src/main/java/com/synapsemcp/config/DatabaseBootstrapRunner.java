package com.synapsemcp.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Creates the app's Postgres database and enables the {@code vector} extension at startup, before
 * any {@code DataSource}/{@code EntityManagerFactory} bean exists (rag_plan.md Stage 0.5) - {@code
 * ddl-auto} can only manage tables inside an already-existing, already-connected database, so this
 * step has to run first and outside the normal bean lifecycle. Implemented as an {@link
 * EnvironmentPostProcessor} (not an {@code ApplicationRunner}) because {@code ApplicationRunner}s
 * execute only after the whole context - including the datasource - has already been created; by
 * then it would be too late.
 *
 * <p>{@code local}/{@code dev}-profile-only ({@code synapsemcp.bootstrap.enabled}, Grooming #18).
 * Reuses the app's own {@code spring.datasource.username}/{@code password} as the bootstrap admin
 * credentials - there is no separate {@code CREATE ROLE} step, since a role that couldn't already
 * authenticate couldn't have been used to establish this very connection in the first place.
 */
public class DatabaseBootstrapRunner implements EnvironmentPostProcessor, Ordered {

    private static final Log log = LogFactory.getLog(DatabaseBootstrapRunner.class);
    private static final String DUPLICATE_DATABASE_SQLSTATE = "42P04";

    /**
     * Postgres has no parameterized-placeholder syntax for a DDL identifier ({@code CREATE DATABASE
     * ?} isn't valid SQL), so string-building the identifier is unavoidable here - flagged by
     * SpotBugs ({@code SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE}, `plan.md` §9 2026-07-17). {@code
     * databaseName} only ever derives from this operator's own {@code spring.datasource.url}
     * config, evaluated once at JVM startup on a {@code local}/{@code dev}-only path - never
     * attacker-reachable - so this is defense-in-depth against a malformed config value, not a
     * response to any real injection path.
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 10;
    }

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("synapsemcp.bootstrap.enabled", Boolean.class, false)) {
            return;
        }

        String targetUrl = environment.getRequiredProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username", "");
        String password = environment.getProperty("spring.datasource.password", "");
        String adminDatabase =
                environment.getProperty("synapsemcp.bootstrap.admin-database", "postgres");

        String databaseName = requireSafeIdentifier(extractDatabaseName(targetUrl));
        String maintenanceUrl = withDatabase(targetUrl, adminDatabase);

        try (Connection connection =
                DriverManager.getConnection(maintenanceUrl, username, password)) {
            createDatabaseIfMissing(connection, databaseName);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to bootstrap database '" + databaseName + "' via " + maintenanceUrl, e);
        }

        try (Connection connection = DriverManager.getConnection(targetUrl, username, password)) {
            enableVectorExtension(connection);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to enable the vector extension on database '" + databaseName + "'", e);
        }
    }

    @SuppressFBWarnings(
            value = "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
            justification =
                    "Postgres has no parameterized-placeholder syntax for a DDL identifier"
                            + " (CREATE DATABASE ? isn't valid SQL). databaseName is validated against"
                            + " SAFE_IDENTIFIER (alphanumeric/underscore only) before reaching this"
                            + " method, and only ever derives from this operator's own"
                            + " spring.datasource.url config on a local/dev-only startup path - not"
                            + " attacker-reachable. SpotBugs can't see the validation step, so this"
                            + " pattern match will always fire here regardless.")
    private void createDatabaseIfMissing(Connection connection, String databaseName)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE \"" + databaseName + "\"");
            log.info("Bootstrapped database '" + databaseName + "'");
        } catch (SQLException e) {
            if (!DUPLICATE_DATABASE_SQLSTATE.equals(e.getSQLState())) {
                throw e;
            }
        }
    }

    private void enableVectorExtension(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
        }
    }

    private static String requireSafeIdentifier(String identifier) {
        if (!SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalStateException(
                    "spring.datasource.url's database name must be alphanumeric/underscore only,"
                            + " got: "
                            + identifier);
        }
        return identifier;
    }

    private static String extractDatabaseName(String jdbcUrl) {
        String tail = jdbcUrl.substring(jdbcUrl.lastIndexOf('/') + 1);
        int queryIndex = tail.indexOf('?');
        return queryIndex >= 0 ? tail.substring(0, queryIndex) : tail;
    }

    private static String withDatabase(String jdbcUrl, String databaseName) {
        String base = jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/'));
        return base + "/" + databaseName;
    }
}
