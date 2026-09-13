package com.bdreview.platform.commerce;

/**
 * What a listing lets customers do beyond browsing. Phase A ships
 * {@link #SHOWCASE_ONLY} (the existing behaviour) and {@link #DIRECT_ORDER}
 * (restaurant ordering); {@link #BOOKING} / {@link #SERVICE_REQUEST} are
 * reserved for later phases and already valid in the DB CHECK.
 */
public enum CommerceMode {
    SHOWCASE_ONLY,
    DIRECT_ORDER,
    BOOKING,
    SERVICE_REQUEST
}
