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
        return isAdminUser(authentication) ? photoModeration.pendingCount() : null;
    }

    /** ADMIN vs MODERATOR — templates use it to hide ADMIN-only navigation/actions (the server blocks them anyway). */
    @ModelAttribute("isAdminUser")
    public boolean isAdminUser(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
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
