package com.bdreview.platform.admin.security;

import java.util.EnumSet;
import java.util.Set;

/**
 * Permission role of an ADMIN account (V67 {@code app_user.admin_role}). Existing admins were
 * migrated to SUPER_ADMIN. MODERATOR here is an admin account with the content sections; it also
 * gets ROLE_MODERATOR so it can use the Community and Promotions review screens exactly like a
 * community moderator (staff flag on a normal account).
 */
public enum AdminRole {
    SUPER_ADMIN("Everything", EnumSet.allOf(AdminPermission.class)),
    MODERATOR("Content, reports, photos, community, reviews, chat reports",
            EnumSet.of(AdminPermission.DASHBOARD, AdminPermission.CONTENT)),
    SUPPORT("Users (read), orders, bookings, offers, support inbox",
            EnumSet.of(AdminPermission.DASHBOARD, AdminPermission.USERS_READ, AdminPermission.COMMERCE,
                    AdminPermission.SUPPORT_INBOX)),
    FINANCE("Boost payments, refunds, revenue, analytics",
            EnumSet.of(AdminPermission.DASHBOARD, AdminPermission.FINANCE, AdminPermission.ANALYTICS));

    private final String summary;
    private final Set<AdminPermission> permissions;

    AdminRole(String summary, Set<AdminPermission> permissions) {
        this.summary = summary;
        this.permissions = permissions;
    }

    public String summary() {
        return summary;
    }

    public Set<AdminPermission> permissions() {
        return permissions;
    }

    /** Unknown/missing values fall back to the least-privileged role, never to SUPER_ADMIN. */
    public static AdminRole parse(String value) {
        if (value == null) {
            return SUPPORT;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return SUPPORT;
        }
    }
}
