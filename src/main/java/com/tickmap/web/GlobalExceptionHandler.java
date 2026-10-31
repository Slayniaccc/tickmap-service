package com.tickmap.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;

/**
 * Every error leaves the API as an RFC 9457 ProblemDetail with a timestamp. Spring's base class
 * already covers the standard MVC exceptions (404, 405, 415...); this adds clearer messages and
 * makes sure unexpected errors never expose internals.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, "One or more fields are invalid");
        pd.setTitle("Validation failed");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(), "message", Objects.requireNonNullElse(e.getDefaultMessage(), "is invalid")))
                .sorted((a, b) -> a.get("field").compareTo(b.get("field")))
                .collect(Collectors.toList());
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, describeUnreadable(ex));
        pd.setTitle("Unreadable request body");
        return handleExceptionInternal(ex, pd, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String type = ex.getRequiredType() == null ? "value" : ex.getRequiredType().getSimpleName();
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status,
                "Parameter '" + ex.getPropertyName() + "' must be a valid " + type + ", got '" + ex.getValue() + "'");
        pd.setTitle("Invalid parameter");
        return handleExceptionInternal(ex, pd, headers, status, request);
    }

    // e.g. ?sort=nonexistent on a paged endpoint without its own whitelist
    @ExceptionHandler(PropertyReferenceException.class)
    ResponseEntity<Object> handleBadSortProperty(PropertyReferenceException ex, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Unknown property '" + ex.getPropertyName() + "'");
        pd.setTitle("Invalid parameter");
        return handleExceptionInternal(ex, pd, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> handleConflict(DataIntegrityViolationException ex, WebRequest request) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The request conflicts with existing data");
        pd.setTitle("Conflict");
        return handleExceptionInternal(ex, pd, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    // The errorId ties the response to the full stack trace in the logs without exposing it.
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        String errorId = UUID.randomUUID().toString();
        log.error("Unhandled error {} on {}", errorId, request.getDescription(false), ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Quote reference " + errorId + " if reporting it.");
        pd.setTitle("Internal error");
        pd.setProperty("errorId", errorId);
        return handleExceptionInternal(ex, pd, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode status,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail pd) {
            pd.setProperty("timestamp", Instant.now());
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    private static String describeUnreadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof JacksonException je && !je.getPath().isEmpty()) {
            String field = je.getPath().stream()
                    .map(r -> r.getPropertyName() != null ? r.getPropertyName() : "[" + r.getIndex() + "]")
                    .collect(Collectors.joining("."));
            String hint = "";
            if (cause instanceof InvalidFormatException ife) {
                Class<?> target = ife.getTargetType();
                if (target == LocalDateTime.class) {
                    hint = "; expected an ISO date-time such as 2025-06-14T17:30:00";
                } else if (target == LocalDate.class) {
                    hint = "; expected an ISO date such as 2025-06-14";
                }
            }
            return "Field '" + field + "' has an invalid value or type" + hint;
        }
        if (cause instanceof JacksonException) {
            return "Request body is not valid JSON or does not match the expected object";
        }
        return "Request body is missing or unreadable";
    }
}
