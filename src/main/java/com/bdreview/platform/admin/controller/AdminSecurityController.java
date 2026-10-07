package com.bdreview.platform.admin.controller;

import com.bdreview.platform.admin.security.AdminRole;
import com.bdreview.platform.admin.security.AdminSecurityService;
import com.bdreview.platform.admin.security.Totp;
import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * V67 admin roles & security. {@code /admin/security} (SUPER_ADMIN): admin accounts and their
 * permission roles, 2FA requirement per role, admin login history, "sign out all admin sessions".
 * {@code /admin/account/2fa} (any staff): enrol in / turn off two-factor sign-in, recovery codes.
 */
@Controller
public class AdminSecurityController {

    private final AdminSecurityService security;
    private final UserRepository userRepository;

    public AdminSecurityController(AdminSecurityService security, UserRepository userRepository) {
        this.security = security;
        this.userRepository = userRepository;
    }

    // ---------------------------------------------------------------- SUPER_ADMIN

    @GetMapping("/admin/security")
    @PreAuthorize("hasRole('ADMIN')")
    public String index(Model model) {
        model.addAttribute("admins", security.admins());
        model.addAttribute("roles", AdminRole.values());
        model.addAttribute("required", security.totpRequirements());
        model.addAttribute("logins", security.adminLogins(200));
        model.addAttribute("active", "security");
        return "admin/security/index";
    }

    @PostMapping("/admin/security/admins/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public String setRole(@PathVariable UUID id, @RequestParam AdminRole role, @RequestParam(required = false) String reason,
                          RedirectAttributes ra) {
        return act(ra, "/admin/security", () -> {
            security.setAdminRole(id, role, reason);
            return "Admin role changed to " + role + ". Their open sessions are signed out on the next request.";
        });
    }

    @PostMapping("/admin/security/admins/{id}/reset-2fa")
    @PreAuthorize("hasRole('ADMIN')")
    public String reset2fa(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        return act(ra, "/admin/security", () -> {
            security.reset(id, reason);
            return "Two-factor sign-in reset — they can enrol again.";
        });
    }

    @PostMapping("/admin/security/2fa-requirements")
    @PreAuthorize("hasRole('ADMIN')")
    public String requirements(HttpServletRequest request, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        return act(ra, "/admin/security", () -> {
            Map<AdminRole, Boolean> required = new EnumMap<>(AdminRole.class);
            for (AdminRole r : AdminRole.values()) {
                required.put(r, request.getParameter("require_" + r.name()) != null);
            }
            security.setTotpRequired(required, reason);
            return "2FA requirements saved. Admins in those roles must enrol before using the panel.";
        });
    }

    @PostMapping("/admin/security/sign-out-all")
    @PreAuthorize("hasRole('ADMIN')")
    public String signOutAll(@RequestParam(required = false) String reason, RedirectAttributes ra) {
        try {
            security.signOutAllSessions(reason);
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/admin/security";
        }
        // This session is one of them — the next request lands on the login page.
        return "redirect:/admin/security";
    }

    // ---------------------------------------------------------------- self-service 2FA (any staff)

    @GetMapping("/admin/account/2fa")
    public String twoFactor(@RequestParam(required = false) String required, Model model) {
        User me = userRepository.findById(CurrentUser.id()).orElseThrow();
        model.addAttribute("me", me);
        model.addAttribute("enrolling", !me.isTotpEnabled() && me.getTotpSecret() != null);
        if (!me.isTotpEnabled() && me.getTotpSecret() != null) {
            model.addAttribute("otpauthUri", Totp.uri(AdminSecurityService.ISSUER, me.getPhoneNumber(), me.getTotpSecret()));
        }
        model.addAttribute("unusedCodes", me.isTotpEnabled() ? security.unusedRecoveryCodes(me.getId()) : 0);
        model.addAttribute("requiredNotice", required != null);
        model.addAttribute("active", "account-2fa");
        return "admin/security/two-factor";
    }

    @PostMapping("/admin/account/2fa/start")
    public String start(RedirectAttributes ra) {
        return act(ra, "/admin/account/2fa", () -> {
            security.startEnrolment(CurrentUser.id());
            return "Scan the QR code with your authenticator app, then enter the 6-digit code.";
        });
    }

    @PostMapping("/admin/account/2fa/confirm")
    public String confirm(@RequestParam String code, RedirectAttributes ra) {
        try {
            List<String> codes = security.confirmEnrolment(CurrentUser.id(), code);
            ra.addFlashAttribute("recoveryCodes", codes);
            ra.addFlashAttribute("successMessage", "Two-factor sign-in is on. Save these recovery codes — they're shown only once.");
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/account/2fa";
    }

    @PostMapping("/admin/account/2fa/recovery-codes")
    public String regenerate(@RequestParam String code, RedirectAttributes ra) {
        User me = userRepository.findById(CurrentUser.id()).orElseThrow();
        if (!me.isTotpEnabled() || !Totp.verify(me.getTotpSecret(), code, java.time.Instant.now())) {
            ra.addFlashAttribute("errorMessage", "Enter a current code from your authenticator app.");
            return "redirect:/admin/account/2fa";
        }
        ra.addFlashAttribute("recoveryCodes", security.regenerateRecoveryCodes(me.getId()));
        ra.addFlashAttribute("successMessage", "New recovery codes created — the old ones no longer work.");
        return "redirect:/admin/account/2fa";
    }

    @PostMapping("/admin/account/2fa/disable")
    public String disable(@RequestParam String code, RedirectAttributes ra) {
        return act(ra, "/admin/account/2fa", () -> {
            security.disable(CurrentUser.id(), code);
            return "Two-factor sign-in is off.";
        });
    }

    private static String act(RedirectAttributes ra, String back, Supplier<String> action) {
        try {
            ra.addFlashAttribute("successMessage", action.get());
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:" + back;
    }
}
