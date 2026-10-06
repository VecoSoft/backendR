package com.bdreview.platform.features;

/** Site-wide maintenance mode is on — answered as 503 carrying the admin's message. */
public class MaintenanceModeException extends RuntimeException {
    public MaintenanceModeException(String message) {
        super(message);
    }
}
