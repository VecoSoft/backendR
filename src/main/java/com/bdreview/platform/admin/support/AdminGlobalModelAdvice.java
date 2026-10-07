package com.bdreview.platform.admin.support;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Makes the logged-in admin's own profile available to every admin-panel template as ${currentAdmin}. */
@ControllerAdvice(basePackages = "com.bdreview.platform.admin.controller")
public class AdminGlobalModelAdvice {

    private final UserRepository userRepository;
    private final com.bdreview.platform.photomod.PhotoModerationService photoModeration;

    public AdminGlobalModelAdvice(UserRepository userRepository,
                                  com.bdreview.platform.photomod.PhotoModerationService photoModeration) {
        this.userRepository = userRepository;
        this.photoModeration = photoModeration;
    }

    /** V63: badge on the sidebar's Moderation → Photos link (ADMIN only — moderators don't see that section). */
    @ModelAttribute("pendingPhotoCount")
    public Long pendingPhotoCount(Authentication authentication) {
        return authentication != null && com.bdreview.platform.admin.security.AdminAuthorities.allows(authentication.getAuthorities(),
                com.bdreview.platform.admin.security.AdminPermission.CONTENT) ? photoModeration.pendingCount() : null;
    }

    /** ADMIN vs MODERATOR — templates use it to hide ADMIN-only navigation/actions (the server blocks them anyway). */
    @ModelAttribute("isAdminUser")
    public boolean isAdminUser(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /**
     * V67: which admin sections this session may open — templates show only those (the server
     * enforces it anyway). Keys: every AdminPermission name, plus COMMUNITY (community screens),
     * PROMO_QUEUE (promotion review queues / boosts) and SUPER (SUPER_ADMIN-only items).
     */
    @ModelAttribute("can")
    public java.util.Map<String, Boolean> can(Authentication authentication) {
        java.util.Map<String, Boolean> can = new java.util.HashMap<>();
        if (authentication == null) {
            return can;
        }
        var granted = authentication.getAuthorities();
        java.util.Set<String> names = new java.util.HashSet<>();
        granted.forEach(a -> names.add(a.getAuthority()));
        boolean superAdmin = names.contains("ROLE_ADMIN");
        for (var perm : com.bdreview.platform.admin.security.AdminPermission.values()) {
            can.put(perm.name(), com.bdreview.platform.admin.security.AdminAuthorities.allows(granted, perm));
        }
        can.put("SUPER", superAdmin);
        can.put("COMMUNITY", superAdmin || names.contains("ROLE_MODERATOR"));
        can.put("PROMO_QUEUE", superAdmin || names.contains("ROLE_MODERATOR") || names.contains("PERM_FINANCE"));
        return can;
    }

    @ModelAttribute("currentAdmin")
    public User currentAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return null;
        }
        try {
            return userRepository.findById(AdminSupport.currentAdminId(authentication)).orElse(null);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
