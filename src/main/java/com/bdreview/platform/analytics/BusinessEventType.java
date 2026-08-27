package com.bdreview.platform.analytics;

/** The only interactions {@code POST /businesses/{id}/events} will accept (Phase 3). */
public enum BusinessEventType {
    PROFILE_VIEW,
    PHONE_CLICK,
    WHATSAPP_CLICK,
    DIRECTIONS_CLICK,
    WEBSITE_CLICK
}
