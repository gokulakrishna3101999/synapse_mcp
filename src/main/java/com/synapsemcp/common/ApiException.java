package com.synapsemcp.common;

import org.springframework.http.HttpStatus;

/**
 * Base type for expected business errors that should surface as an RFC 7807 problem-detail response
 * (plan.md §4 Code conventions: "All API errors use RFC 7807 problem-details"). Domain services
 * throw this (or a subclass) instead of an ad-hoc exception; {@link ApiExceptionHandler} translates
 * it.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String title;

    public ApiException(HttpStatus status, String title, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }
}
