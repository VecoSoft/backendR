package com.bdreview.platform.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** V67 System → Health "recent errors" (in-memory ring buffer). */
    private final com.bdreview.platform.health.RecentErrors recentErrors;

    public GlobalExceptionHandler(com.bdreview.platform.health.RecentErrors recentErrors) {
        this.recentErrors = recentErrors;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> handleBadRequest(BadRequestException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> handleForbidden(ForbiddenException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), req);
    }

    /** Community switched off by an admin — 503 carrying the admin's maintenance message. */
    @ExceptionHandler(com.bdreview.platform.community.moderation.CommunityUnavailableException.class)
    public ResponseEntity<ApiError> handleCommunityUnavailable(RuntimeException ex, HttpServletRequest req) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), req);
    }

    /** @PreAuthorize denials — without this the generic handler below would turn them into a 500. */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "Access denied", req);
    }

    /** Disabled features (e.g. NID verification while its flag is off) answer 404 "Feature disabled". */
    @ExceptionHandler(FeatureDisabledException.class)
    public ResponseEntity<ApiError> handleFeatureDisabled(FeatureDisabledException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req);
    }

    /** Site-wide maintenance mode (V63) — 503 carrying the admin's message. */
    @ExceptionHandler(com.bdreview.platform.features.MaintenanceModeException.class)
    public ResponseEntity<java.util.Map<String, Object>> handleMaintenance(RuntimeException ex, HttpServletRequest req) {
        java.util.Map<String, Object> body = errorBody(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), req);
        body.put("code", "MAINTENANCE");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /** Suspended/banned account (V63) — 403 plus the code, the admin's reason and the end date (null for a ban). */
    @ExceptionHandler(com.bdreview.platform.accountcontrol.AccountRestrictedException.class)
    public ResponseEntity<java.util.Map<String, Object>> handleAccountRestricted(
            com.bdreview.platform.accountcontrol.AccountRestrictedException ex, HttpServletRequest req) {
        java.util.Map<String, Object> body = errorBody(HttpStatus.FORBIDDEN, ex.getMessage(), req);
        body.put("code", ex.code());
        body.put("reason", ex.reason());
        body.put("endsAt", ex.endsAt());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    private static java.util.Map<String, Object> errorBody(HttpStatus status, String message, HttpServletRequest req) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("timestamp", Instant.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", req.getRequestURI());
        return body;
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> handleRateLimit(RateLimitExceededException ex, HttpServletRequest req) {
        return build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), req);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, message, req);
    }

    /** Unparseable JSON body or a value that doesn't fit the target type (e.g. an unknown enum constant). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Malformed or invalid request body", req);
    }

    /** Bad path/query parameter type (e.g. a non-UUID id). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Invalid value for '" + ex.getName() + "'", req);
    }

    /**
     * A real endpoint, wrong HTTP verb (e.g. PUT on a route that only maps PATCH) — without this,
     * the broad Exception.class handler below catches it before Spring's own default resolver
     * ever gets a chance, downgrading a correct 405 into a misleading 500 "Unexpected error".
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage(), req);
    }

    /** No route matches at all (typo'd/nonexistent endpoint) — same "don't let Exception.class turn this into a 500" reasoning as above. */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiError> handleNoHandlerFound(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "No such endpoint", req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        recentErrors.record(req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", req);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest req) {
        ApiError body = new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, req.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
