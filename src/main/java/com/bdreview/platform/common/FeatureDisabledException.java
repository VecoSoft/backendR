package com.bdreview.platform.common;

/** A feature switched off by its flag — answered as 404 "Feature disabled" (see GlobalExceptionHandler). */
public class FeatureDisabledException extends RuntimeException {
    public FeatureDisabledException() {
        super("Feature disabled");
    }
}
