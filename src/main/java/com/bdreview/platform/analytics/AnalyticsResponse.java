package com.bdreview.platform.analytics;

import java.time.Instant;

/**
 * Owner "Business performance" numbers for a date range. Just five counts plus
 * the window they cover — no time series, no per-day breakdown (Phase 3 keeps
 * it to metric cards).
 */
public record AnalyticsResponse(
        String range,       // "7d" | "30d" | "all"
        Instant from,       // null for "all"
        long profileViews,
        long phoneClicks,
        long whatsappClicks,
        long directionsClicks,
        long websiteClicks
) {
}
