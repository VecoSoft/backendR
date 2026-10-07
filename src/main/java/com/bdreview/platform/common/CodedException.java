package com.bdreview.platform.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * An API error the app reacts to by its machine-readable {@code code} (for example
 * EMAIL_NOT_VERIFIED opens the code-entry screen), not just by its message. The JSON body is the
 * usual error body plus {@code code} and any {@code details}.
 */
public class CodedException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public CodedException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public CodedException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
