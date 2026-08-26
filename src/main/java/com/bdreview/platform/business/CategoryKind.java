package com.bdreview.platform.business;

/**
 * Canonical, machine-readable classification of a {@link Category} — the single
 * source of truth for which category-specific showcase modules a listing gets
 * (Phase 2). The category <em>name</em> stays free-text and admin-created; this
 * enum is set (best-effort backfilled from the name, then admin-correctable).
 * {@link #GENERAL} is the safe fallback and the column default.
 */
public enum CategoryKind {
    RESTAURANT,
    CLINIC,
    SALON,
    RETAIL,
    GYM,
    GENERAL
}
