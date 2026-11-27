package com.ledgerguard.common;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders errors as RFC 9457 Problem Details. Expected business errors keep their message;
 * unexpected failures never expose SQL, stack traces or internal class names.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException e) {
        return problem(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleIntegrity(DataIntegrityViolationException e) {
        log.error("Database integrity constraint rejected an operation", e);
        return problem(HttpStatus.CONFLICT, "DATA_INTEGRITY_VIOLATION",
                "The operation was rejected because it would violate a ledger integrity rule. No changes were committed.");
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    public ProblemDetail handleConcurrency(ConcurrencyFailureException e) {
        log.warn("Concurrency failure after retries", e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "RETRY_LATER",
                "The request could not be completed due to contention. No changes were committed; retry with the same Idempotency-Key.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. No partial financial changes were committed.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            @NonNull MethodArgumentNotValidException ex,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request) {
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "One or more fields are invalid");
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        detail.setProperty("fieldErrors", fieldErrors);
        return ResponseEntity.badRequest().body(detail);
    }

    public static ProblemDetail problem(HttpStatus status, String code, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setType(URI.create("urn:ledgerguard:problem:" + code.toLowerCase().replace('_', '-')));
        detail.setTitle(status.getReasonPhrase());
        detail.setProperty("code", code);
        detail.setProperty("correlationId", CorrelationIdFilter.current());
        return detail;
    }
}
