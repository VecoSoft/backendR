package com.bdreview.platform.common;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/** Reads the authenticated user's id/role, set by auth.JwtAuthenticationFilter. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth instanceof AnonymousAuthenticationToken) {
            throw new ForbiddenException("Not authenticated");
        }
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            throw new ForbiddenException("Not authenticated");
        }
    }

    /** Same as id(), but returns null instead of throwing — for endpoints that stay public for an anonymous caller. */
    public static UUID idOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }

    /**
     * Any granted authority, e.g. an admin-panel permission {@code PERM_CONTENT} (V67). ROLE_ADMIN
     * (SUPER_ADMIN) implies every {@code PERM_*}.
     */
    public static boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(authority)
                || (authority.startsWith("PERM_") && a.getAuthority().equals("ROLE_ADMIN")));
    }

    public static void requireAuthority(String authority) {
        if (!hasAuthority(authority)) {
            throw new ForbiddenException("You don't have access to this admin section");
        }
    }

    public static void requireRole(String role) {
        if (!hasRole(role)) {
            throw new ForbiddenException("Requires role " + role);
        }
    }
}
