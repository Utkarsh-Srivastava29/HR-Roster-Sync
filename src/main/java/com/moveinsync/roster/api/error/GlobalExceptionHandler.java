package com.moveinsync.roster.api.error;

import com.moveinsync.roster.service.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.net.URI;
import java.util.List;

/**
 * Every error becomes an RFC 7807 problem with a stable {@code code} and the {@code requestId}
 * (to find the log line). Messages are written by us, never copied from input: rejected values,
 * stack traces and exception messages from libraries are not returned, so nothing personal
 * (phone, email, home location) can leak through an error.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e) {
        if (e.httpStatus() >= 500) LOG.warn("request failed: {} ({})", e.code(), e.getClass().getSimpleName());
        return problem(e.httpStatus(), e.code(), e.getMessage(), null);
    }

    /** Field names only - never the rejected values. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
        List<String> fields = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField()).distinct().sorted().toList();
        return problem(400, "INVALID_REQUEST", "invalid fields: " + String.join(", ", fields), fields);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> invalidParams(HandlerMethodValidationException e) {
        return problem(400, "INVALID_REQUEST", "invalid request parameters", null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e) {
        return problem(400, "MALFORMED_JSON", "request body is not valid JSON for this endpoint", null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(AccessDeniedException e) {
        return problem(403, "FORBIDDEN", "your role cannot do this", null);
    }

    /** Spring's own errors (404 for unknown routes, 405, 415 ...): keep the status, use our wording. */
    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, ErrorResponseException.class})
    ResponseEntity<ProblemDetail> spring(Exception e) {
        int status = e instanceof ErrorResponse er ? er.getStatusCode().value() : 400;
        HttpStatus hs = HttpStatus.resolve(status);
        return problem(status, hs == null ? "ERROR" : hs.name(), hs == null ? "request failed" : hs.getReasonPhrase(), null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        // full detail goes to the log (which has no personal data either), not to the client
        LOG.error("unexpected error", e);
        return problem(500, "INTERNAL_ERROR", "something went wrong; quote the requestId when reporting it", null);
    }

    private static ResponseEntity<ProblemDetail> problem(int status, String code, String detail, List<String> fields) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setType(URI.create("https://errors.roster.local/" + code.toLowerCase()));
        pd.setTitle(code);
        pd.setDetail(detail);
        pd.setProperty("code", code);
        pd.setProperty("requestId", MDC.get("requestId"));
        if (fields != null) pd.setProperty("fields", fields);
        return ResponseEntity.status(status).body(pd);
    }
}
