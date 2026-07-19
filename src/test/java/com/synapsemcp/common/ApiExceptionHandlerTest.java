package com.synapsemcp.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
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

    /**
     * Found live (audit session, 2026-07-17): no Postgres {@code lock_timeout} was configured
     * anywhere (Postgres's own default is {@code 0}, disabled) - manually held a {@code SELECT ...
     * FOR UPDATE} lock on a tenant row for 25s and a concurrent knowledge_base creation for that
     * tenant waited the *entire* duration with no timeout at all, before this handler/the {@code
     * lock_timeout} fix existed. Distinct from the DB-unavailable case above: the database is up,
     * just contended - a transient, retry-able condition, hence {@code Retry-After}.
     */
    @Test
    void translatesLockTimeoutTo503WithRetryAfterNotAGeneric500() throws Exception {
        mockMvc.perform(get("/test/lock-timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Service Unavailable"))
                .andExpect(header().string("Retry-After", "3"));
    }

    /**
     * Found live (audit session, 2026-07-17): firing concurrent {@code PUT}/{@code DELETE} requests
     * at the same knowledge base reliably produced unhandled {@code 500}s - Hibernate checks the
     * affected row count on every UPDATE/DELETE by id regardless of whether the entity has a
     * {@code @Version} field, and throws when a concurrent request already deleted the row. Mapped
     * to {@code 404}, confirmed with the user, since the only way this fires here is the row being
     * gone by the time this write executes - indistinguishable from "never existed."
     */
    @Test
    void translatesConcurrentDeleteRaceTo404NotAServerError() throws Exception {
        mockMvc.perform(get("/test/optimistic-lock-failure"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not Found"));
    }

    /**
     * rag_plan.md Stage 4: Spring's own multipart resolver enforces {@code
     * spring.servlet.multipart.max-file-size} before {@code DocumentUploadService} ever runs.
     * Confirmed via {@code javap} that without this explicit handler, this class's own broader
     * {@code @ExceptionHandler(Exception.class)} would catch it first (registered handlers in one
     * {@code @RestControllerAdvice} take priority over Spring's default {@code ErrorResponse}-aware
     * resolver), leaking as a {@code 500} instead of the {@code 413} the exception itself already
     * self-describes as. The title override matches {@code RequestBodySizeLimitFilter}'s own
     * hardcoded {@code 413} wording, not the raw {@code HttpStatus.CONTENT_TOO_LARGE} reason phrase
     * ("Content Too Large") - both {@code 413} paths in this API should read the same way.
     */
    @Test
    void translatesUploadTooLargeTo413NotAServerError() throws Exception {
        mockMvc.perform(get("/test/upload-too-large"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.title").value("Payload Too Large"));
    }

    /**
     * Found live (a later, more exhaustive audit pass, same day, `plan.md` §9 2026-07-17) after
     * generalizing the narrow {@code MaxUploadSizeExceededException}-only fix above to the whole
     * {@code ErrorResponse} family: this is a real Spring MVC routing failure, not a
     * hand-constructed exception, confirming the generic handler actually engages for the genuine
     * framework-thrown case, not just a directly-thrown test double. Confirmed live via curl
     * against a real running app that this affects every endpoint in the API, not just Stage 4's
     * upload path.
     */
    @Test
    void translatesWrongHttpMethodTo405NotAServerError() throws Exception {
        mockMvc.perform(post("/test/type-mismatch/" + UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed());
    }

    /** Same generalization as above - a genuine Spring MVC content-negotiation failure. */
    @Test
    void translatesWrongContentTypeTo415NotAServerError() throws Exception {
        mockMvc.perform(
                        post("/test/validated")
                                .contentType(MediaType.TEXT_PLAIN)
                                .content("irrelevant"))
                .andExpect(status().isUnsupportedMediaType());
    }

    /**
     * Same generalization - a missing required multipart part is the exact scenario found live
     * against the real Stage 4 upload endpoint (a client omitting the {@code "file"} part
     * entirely).
     */
    @Test
    void translatesMissingMultipartPartTo400NotAServerError() throws Exception {
        mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .multipart("/test/multipart"))
                .andExpect(status().isBadRequest());
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

        @GetMapping("/test/lock-timeout")
        void throwLockTimeout() {
            throw new CannotAcquireLockException(
                    "could not execute statement",
                    new SQLException("canceling statement due to lock timeout", "55P03"));
        }

        @GetMapping("/test/optimistic-lock-failure")
        void throwOptimisticLockFailure() {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
                    "com.synapsemcp.knowledgebase.KnowledgeBase", UUID.randomUUID());
        }

        @GetMapping("/test/upload-too-large")
        void throwUploadTooLarge() {
            throw new org.springframework.web.multipart.MaxUploadSizeExceededException(
                    20 * 1024 * 1024);
        }

        @PostMapping("/test/multipart")
        void requiresAMultipartFile(
                @org.springframework.web.bind.annotation.RequestParam("file")
                        org.springframework.web.multipart.MultipartFile file) {}
    }

    record TestBody(@NotBlank String name) {}
}
