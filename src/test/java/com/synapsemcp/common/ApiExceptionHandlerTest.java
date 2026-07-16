package com.synapsemcp.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(new TestController())
                        .setControllerAdvice(new ApiExceptionHandler())
                        .build();
    }

    @Test
    void translatesApiExceptionToProblemDetail() throws Exception {
        mockMvc.perform(get("/test/api-exception"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("tenant not found"));
    }

    @Test
    void translatesUnexpectedExceptionTo500WithoutLeakingInternals() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
    }

    @Test
    void translatesValidationFailureTo400WithFieldErrors() throws Exception {
        mockMvc.perform(
                        post("/test/validated")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.fieldErrors.name").exists());
    }

    @Test
    void translatesMalformedJsonTo400NotAServerError() throws Exception {
        mockMvc.perform(
                        post("/test/validated")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value("Malformed request body"));
    }

    @Test
    void translatesDatabaseUnavailableTo503NotAGeneric500() throws Exception {
        mockMvc.perform(get("/test/db-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Service Unavailable"))
                .andExpect(jsonPath("$.detail").value("Database temporarily unavailable"));
    }

    /**
     * Distinct from the test above: a repository call whose *own* connection acquisition fails
     * (Hibernate's {@code JDBCConnectionException}, translated to this type) throws a completely
     * different exception hierarchy than a failed-to-open-a-transaction failure, verified live with
     * Postgres stopped mid-flight - see {@code ApiExceptionHandler}'s Javadoc on this handler.
     */
    @Test
    void translatesDataAccessResourceFailureTo503NotAGeneric500() throws Exception {
        mockMvc.perform(get("/test/db-connection-lost"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Service Unavailable"))
                .andExpect(jsonPath("$.detail").value("Database temporarily unavailable"));
    }

    /**
     * Found live (audit session, 2026-07-17): a malformed UUID path variable (e.g. a typo'd {@code
     * tenantId}) threw {@code MethodArgumentTypeMismatchException}, unhandled, and leaked a raw
     * {@code 500} - the same "client mistake misclassified as server bug" pattern already fixed
     * once for malformed JSON bodies.
     */
    @Test
    void translatesTypeMismatchTo400NotAServerError() throws Exception {
        mockMvc.perform(get("/test/type-mismatch/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"));
    }

    /**
     * Found live: 9 of 10 concurrent first-time {@code PUT model-config} calls for the same
     * brand-new tenant crashed with a raw {@code 500} - {@code ModelConfigService}'s non-atomic
     * find-or-create has no locking, so a losing race hits {@code model_configs.tenant_id}'s unique
     * constraint. A real conflict with existing state is a {@code 409}, not a {@code 500}.
     */
    @Test
    void translatesUniqueConstraintViolationTo409NotAServerError() throws Exception {
        mockMvc.perform(get("/test/unique-violation"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"));
    }

    /**
     * A non-unique constraint failure (e.g. Hibernate's implicit {@code varchar(255)} rejecting an
     * oversized {@code CreateTenantRequest.name}, also found live) is a malformed request, not a
     * conflict - {@code 400}, not {@code 409}.
     */
    @Test
    void translatesOtherDataIntegrityViolationTo400NotAConflict() throws Exception {
        mockMvc.perform(get("/test/data-integrity-other"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value("Invalid request data"));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/api-exception")
        void throwApiException() {
            throw new ApiException(HttpStatus.NOT_FOUND, "Not Found", "tenant not found");
        }

        @GetMapping("/test/unexpected")
        void throwUnexpected() {
            throw new RuntimeException("boom - should never reach the client");
        }

        @GetMapping("/test/db-unavailable")
        void throwDatabaseUnavailable() {
            throw new CannotCreateTransactionException(
                    "Could not open JPA EntityManager for transaction");
        }

        @GetMapping("/test/db-connection-lost")
        void throwDataAccessResourceFailure() {
            throw new DataAccessResourceFailureException("Unable to acquire JDBC Connection");
        }

        @PostMapping("/test/validated")
        void validated(@Valid @RequestBody TestBody body) {}

        @GetMapping("/test/type-mismatch/{id}")
        void throwTypeMismatch(@PathVariable UUID id) {}

        @GetMapping("/test/unique-violation")
        void throwUniqueViolation() {
            throw new DataIntegrityViolationException(
                    "duplicate",
                    new SQLException("duplicate key value violates unique constraint", "23505"));
        }

        @GetMapping("/test/data-integrity-other")
        void throwOtherDataIntegrityViolation() {
            throw new DataIntegrityViolationException(
                    "too long",
                    new SQLException("value too long for type character varying(255)", "22001"));
        }
    }

    record TestBody(@NotBlank String name) {}
}
