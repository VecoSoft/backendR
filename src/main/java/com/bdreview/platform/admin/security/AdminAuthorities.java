package com.bdreview.platform.admin.security;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;

/** One place that turns an account into its admin-panel authorities (session login and JWT alike). */
public final class AdminAuthorities {

    private AdminAuthorities() {
    }

    /**
     * ADMIN accounts: PERM_* for their role's sections, ROLE_ADMIN only for SUPER_ADMIN, ROLE_MODERATOR
     * for the MODERATOR role and ROLE_ADMIN_STAFF for every admin. Community moderators (staff flag
     * on a normal account): ROLE_MODERATOR only. Everyone else: nothing.
     */
    public static List<GrantedAuthority> of(User user) {
        List<GrantedAuthority> out = new ArrayList<>();
        if (user.getRole() == UserRole.ADMIN) {
            AdminRole role = AdminRole.parse(user.getAdminRole());
            out.add(new SimpleGrantedAuthority("ROLE_ADMIN_STAFF"));
            if (role == AdminRole.SUPER_ADMIN) {
                out.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            }
            if (role == AdminRole.MODERATOR) {
                out.add(new SimpleGrantedAuthority("ROLE_MODERATOR"));
            }
            role.permissions().forEach(p -> out.add(new SimpleGrantedAuthority(p.authority())));
        } else if (user.isModerator()) {
            out.add(new SimpleGrantedAuthority("ROLE_MODERATOR"));
        }
        return out;
    }

    /** True while the account still holds every authority a session was granted. */
    public static boolean stillGranted(java.util.Collection<? extends GrantedAuthority> granted, User user) {
        java.util.Set<String> current = new java.util.HashSet<>();
        of(user).forEach(a -> current.add(a.getAuthority()));
        return granted.stream().map(GrantedAuthority::getAuthority).allMatch(current::contains);
    }

    /** ROLE_ADMIN (SUPER_ADMIN) implies every section. */
    public static boolean allows(java.util.Collection<? extends GrantedAuthority> granted, AdminPermission permission) {
        return granted.stream().map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals(permission.authority()));
    }
}
