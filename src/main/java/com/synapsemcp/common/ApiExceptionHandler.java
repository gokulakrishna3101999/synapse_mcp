package com.synapsemcp.common;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates exceptions into RFC 7807 problem-details (plan.md §4). Only covers exceptions raised
 * *inside* Spring MVC's dispatched request handling - {@link ApiKeyAuthenticationFilter} runs
 * earlier in the filter chain and writes its own minimal problem-detail body directly for that
 * reason.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException e) {
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
        problemDetail.setTitle(e.getTitle());
        return problemDetail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException e) {
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST, "Request validation failed");
        problemDetail.setTitle("Bad Request");
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            String message = fieldError.getDefaultMessage();
            fieldErrors.put(fieldError.getField(), message != null ? message : "invalid");
        }
        problemDetail.setProperty("fieldErrors", fieldErrors);
        return problemDetail;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleMalformedRequestBody(HttpMessageNotReadableException e) {
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request body");
        problemDetail.setTitle("Bad Request");
        return problemDetail;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_REQUEST,
                        "Invalid value for parameter '" + e.getName() + "'");
        problemDetail.setTitle("Bad Request");
        return problemDetail;
    }

    private static final String UNIQUE_VIOLATION_SQLSTATE = "23505";

    /**
     * A {@code DataIntegrityViolationException} covers several genuinely different Postgres
     * constraint failures (unique, check, not-null, string-too-long) that need different status
     * codes: a losing race on a unique constraint (e.g. two concurrent first-time {@code PUT
     * model-config} calls for the same tenant - both pass the app-layer "does a row exist" check,
     * only one wins the insert) is a real conflict with existing state ({@code 409}); every other
     * constraint failure here (oversized input, invalid data shape) is a malformed request ({@code
     * 400}) - found live: 9 of 10 concurrent first-time model-config writes for one tenant crashed
     * with an unhandled {@code 500} before this handler existed, and a &gt;255-char tenant name
     * (Hibernate's implicit {@code varchar(255)}) did too, via the same unhandled exception type.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException e) {
        if (isUniqueViolation(e)) {
            log.warn("Unique constraint violated: {}", e.getMessage());
            ProblemDetail problemDetail =
                    ProblemDetail.forStatusAndDetail(
                            HttpStatus.CONFLICT, "Request conflicts with existing state");
            problemDetail.setTitle("Conflict");
            return problemDetail;
        }
        log.warn("Data integrity violation: {}", e.getMessage());
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request data");
        problemDetail.setTitle("Bad Request");
        return problemDetail;
    }

    private boolean isUniqueViolation(DataIntegrityViolationException e) {
        Throwable mostSpecificCause = e.getMostSpecificCause();
        return mostSpecificCause instanceof SQLException sqlException
                && UNIQUE_VIOLATION_SQLSTATE.equals(sqlException.getSQLState());
    }

    /**
     * Two distinct, non-overlapping Spring exception hierarchies both mean the same thing here
     * ("Postgres is unreachable mid-request") depending on exactly when the failure hits: {@link
     * CannotCreateTransactionException} (a {@code TransactionException}) when an outer
     * {@code @Transactional} boundary fails to open its transaction at all - verified live via
     * {@code TenantService.createTenant()} with Postgres stopped mid-flight; {@link
     * DataAccessResourceFailureException} (a {@code DataAccessException}, Hibernate's own {@code
     * JDBCConnectionException} translated) when a repository call's own connection acquisition
     * fails instead - verified live via {@code ApiKeyAuthenticationFilter}'s repository call (which
     * also has its own copy of this same mapping, since that filter runs before this class ever
     * sees anything - see its Javadoc).
     */
    @ExceptionHandler({
        CannotCreateTransactionException.class,
        DataAccessResourceFailureException.class
    })
    public ProblemDetail handleDatabaseUnavailable(Exception e) {
        log.warn("Database unavailable: {}", e.getMessage());
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.SERVICE_UNAVAILABLE, "Database temporarily unavailable");
        problemDetail.setTitle("Service Unavailable");
        return problemDetail;
    }

    /**
     * A row lock (e.g. {@code KnowledgeBaseService}'s pessimistic tenant-row lock for the
     * 10-KB-per-tenant race guard) hit Postgres's own {@code lock_timeout} (found live, `plan.md`
     * §9 2026-07-17: with no explicit bound configured - Postgres's own default is {@code 0},
     * disabled - a manually-held lock on a tenant row made a concurrent request wait the *entire*
     * hold duration with no timeout at all; fixed by setting an explicit {@code lock_timeout} via
     * HikariCP's {@code connection-init-sql}). Distinct from {@link #handleDatabaseUnavailable}:
     * the database is up and reachable here, just contended by another transaction - a transient,
     * retry-able condition, not an outage, hence the {@code Retry-After} header.
     */
    @ExceptionHandler(CannotAcquireLockException.class)
    public ResponseEntity<ProblemDetail> handleLockTimeout(CannotAcquireLockException e) {
        log.warn("Row lock not acquired in time: {}", e.getMessage());
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Resource temporarily locked by another request, try again shortly");
        problemDetail.setTitle("Service Unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "3")
                .body(problemDetail);
    }

    /**
     * Fires when an entity fetched earlier in the same request (e.g. {@code
     * KnowledgeBaseService.requireOwnedKnowledgeBase}) is then updated or deleted by primary key
     * after a <i>different</i> concurrent request has already deleted the same row - Hibernate
     * checks the affected row count on every UPDATE/DELETE by id regardless of whether the entity
     * has a {@code @Version} field, and throws this (wrapping {@code StaleObjectStateException})
     * when it's 0 instead of the expected 1. Found live (audit session, 2026-07-17): firing
     * concurrent {@code PUT}/{@code DELETE} requests at the same knowledge base reliably produced
     * unhandled {@code 500}s via exactly this path before this handler existed. Mapped to {@code
     * 404}, not {@code 409} - confirmed with the user - since the only way this specific exception
     * fires here is a concurrent delete already removed the row, which is indistinguishable from
     * "never existed" by the time this response is written, matching the same {@code 404} contract
     * {@code requireOwnedKnowledgeBase} already uses for that exact case.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleConcurrentDelete(ObjectOptimisticLockingFailureException e) {
        log.warn("Write raced a concurrent delete of the same resource: {}", e.getMessage());
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.NOT_FOUND, "Resource was deleted by a concurrent request");
        problemDetail.setTitle("Not Found");
        return problemDetail;
    }

    /**
     * A whole family of Spring MVC framework exceptions self-describe their correct HTTP response
     * via the {@code ErrorResponse} interface (Framework 6+/7's standard mechanism for this) - but
     * without an explicit handler here, every one of them is still caught by this class's own
     * broader {@link #handleUnexpected} first (registered {@code @ExceptionHandler} methods in one
     * {@code @RestControllerAdvice} take priority over Spring's default {@code ErrorResponse}-aware
     * resolver), leaking as an unhelpful {@code 500} instead of the status/body the exception
     * already carries.
     *
     * <p>Generalized from a narrower fix for just {@link MaxUploadSizeExceededException} (`plan.md`
     * §9, earlier same day) after a later audit pass found the same shape of bug live, repeatedly,
     * across unrelated endpoints - not just Stage 4's new upload path: a missing multipart {@code
     * "file"} part ({@link MissingServletRequestPartException}, {@code 400}), a wrong {@code
     * Content-Type} on the upload endpoint ({@link HttpMediaTypeException}, {@code 415}), and a
     * wrong HTTP method on an ordinary JSON endpoint ({@link
     * HttpRequestMethodNotSupportedException}, {@code 405}) all `500`d before this generalization.
     * Rather than add one narrow handler per exception type discovered by accident, every {@code
     * ErrorResponse}-implementing exception reachable from this app's actual request-handling flow
     * is listed here, found by scanning every class in {@code spring-web}/{@code spring-webmvc}
     * implementing that interface, not guessed.
     *
     * <p>Deliberately excludes {@link MethodArgumentNotValidException}, which also implements
     * {@code ErrorResponse} but already has its own more specific handler above with custom
     * field-error formatting - listing it here too would be a duplicate {@code @ExceptionHandler}
     * registration for the same type. The method parameter is typed as the {@link ErrorResponse}
     * interface itself (not {@code Exception}) - Spring resolves the handler by the concrete types
     * listed in {@code value()}, then binds the actual thrown instance to this common interface,
     * which every listed type implements by construction.
     */
    @ExceptionHandler({
        HttpMediaTypeException.class,
        MissingServletRequestPartException.class,
        MaxUploadSizeExceededException.class,
        HttpRequestMethodNotSupportedException.class,
        ServletRequestBindingException.class,
        NoHandlerFoundException.class,
        NoResourceFoundException.class,
        AsyncRequestTimeoutException.class,
        ResponseStatusException.class
    })
    public ResponseEntity<ProblemDetail> handleSelfDescribingSpringMvcException(ErrorResponse e) {
        log.warn("Spring MVC rejected the request: {}", e.getBody().getDetail());
        ProblemDetail problemDetail = e.getBody();
        if (e.getStatusCode().value() == HttpStatus.CONTENT_TOO_LARGE.value()) {
            // Matches RequestBodySizeLimitFilter's own hardcoded 413 title (that filter runs
            // outside Spring MVC entirely, so it can't share this handler) - HttpStatus.
            // CONTENT_TOO_LARGE's own reason phrase ("Content Too Large") would otherwise give the
            // two 413 paths in this API different titles for the same status.
            problemDetail.setTitle("Payload Too Large");
        } else if (problemDetail.getTitle() == null) {
            problemDetail.setTitle(HttpStatus.valueOf(e.getStatusCode().value()).getReasonPhrase());
        }
        return ResponseEntity.status(e.getStatusCode()).body(problemDetail);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problemDetail.setTitle("Internal Server Error");
        return problemDetail;
    }
}
