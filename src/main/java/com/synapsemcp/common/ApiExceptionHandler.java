package com.synapsemcp.common;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
