package com.bdreview.platform.common;

/** The request was valid but the resource's current state can no longer satisfy it (HTTP 409) — e.g. a booking slot just got taken by someone else. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
