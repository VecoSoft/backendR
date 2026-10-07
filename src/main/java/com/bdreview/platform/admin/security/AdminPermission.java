package com.bdreview.platform.admin.security;

/**
 * Admin-panel sections (V67). A session or token carries {@code PERM_<NAME>} for every section its
 * admin role allows; SUPER_ADMIN additionally keeps {@code ROLE_ADMIN}, which the SYSTEM-level and
 * pre-existing ADMIN-only checks still require.
 */
public enum AdminPermission {
    /** Dashboard landing page. */
    DASHBOARD,
    /** Reviews, reports, photos, business claims, chat-report moderation. */
    CONTENT,
    /** Users list and user pages (read). */
    USERS_READ,
    /** Suspend/ban/role changes, password resets, new admin accounts. */
    USERS_MANAGE,
    /** Businesses, categories/areas, verification, pending changes, duplicates, data checks. */
    CATALOG,
    /** Orders, bookings, offers and their cancel/close actions. */
    COMMERCE,
    /** Boost payments, refunds, revenue. */
    FINANCE,
    /** Analytics (read). */
    ANALYTICS,
    /** Support inbox. */
    SUPPORT_INBOX,
    /** Settings, homepage, notifications, health, content pages, audit log, admin security. */
    SYSTEM;

    public String authority() {
        return "PERM_" + name();
    }
}
