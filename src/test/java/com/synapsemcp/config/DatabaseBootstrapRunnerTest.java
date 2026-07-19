package com.synapsemcp.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Covers the identifier-validation guard added alongside a SpotBugs pass ({@code
 * SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE}, `plan.md` §9 2026-07-17) - deliberately not exercising
 * {@code postProcessEnvironment}'s successful path here, since that requires a real Postgres
 * connection ({@code DriverManager.getConnection}, not Spring-managed) and is already covered by
 * this project's live startup against the real local database. This test only needs the failure
 * path, which - by construction - throws before any connection is ever attempted.
 */
class DatabaseBootstrapRunnerTest {

    private final DatabaseBootstrapRunner runner = new DatabaseBootstrapRunner();

    @Test
    void rejectsADatabaseNameContainingSqlMetacharactersBeforeAttemptingAnyConnection() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("synapsemcp.bootstrap.enabled", "true");
        environment.setProperty(
                "spring.datasource.url",
                "jdbc:postgresql://localhost:5432/db\"; DROP TABLE users; --");
        environment.setProperty("spring.datasource.username", "test");
        environment.setProperty("spring.datasource.password", "test");

        assertThatThrownBy(() -> runner.postProcessEnvironment(environment, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alphanumeric/underscore only");
    }

    @Test
    void doesNothingWhenBootstrapIsDisabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("synapsemcp.bootstrap.enabled", "false");

        runner.postProcessEnvironment(environment, null);
    }
}
