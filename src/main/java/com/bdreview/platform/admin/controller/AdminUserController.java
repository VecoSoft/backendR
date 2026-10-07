package com.bdreview.platform.admin.controller;

import com.bdreview.platform.accountcontrol.AccountControlService;
import com.bdreview.platform.admin.form.UserForm;
import com.bdreview.platform.admin.service.AdminUserActivityService;
import com.bdreview.platform.admin.service.AdminUserService;
import com.bdreview.platform.admin.support.AdminSupport;
import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.community.moderation.CommunityModerationService;
import com.bdreview.platform.moderation.AuditLogRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Controller
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','PERM_USERS_MANAGE')")
@RequestMapping("/admin/users")
public class AdminUserController {

    /** List filter values for the account status. */
    private static final List<String> STATUSES = List.of("ACTIVE", "SUSPENDED", "BANNED");
    /** Change-role pseudo-roles for the community staff flag (User.staffRole), next to the account types. */
    private static final String MODERATOR = "MODERATOR";
    private static final String REMOVE_MODERATOR = "REMOVE_MODERATOR";

    private final AdminUserService adminUserService;
    private final BusinessRepository businessRepository;
    private final AccountControlService accountControl;
    private final AdminUserActivityService activityService;
    private final AuditLogRepository auditLogRepository;
    private final CommunityModerationService moderation;

    public AdminUserController(AdminUserService adminUserService, BusinessRepository businessRepository,
                               AccountControlService accountControl, AdminUserActivityService activityService,
                               AuditLogRepository auditLogRepository, CommunityModerationService moderation) {
        this.adminUserService = adminUserService;
        this.businessRepository = businessRepository;
        this.accountControl = accountControl;
        this.activityService = activityService;
        this.auditLogRepository = auditLogRepository;
        this.moderation = moderation;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','PERM_USERS_READ')")
    public String list(@RequestParam(required = false) String query,
                        @RequestParam(required = false) UserRole role,
                        @RequestParam(required = false) String status,
                        @RequestParam(required = false) Integer page,
                        Model model) {
        String statusFilter = status != null && STATUSES.contains(status) ? status : null;
        var results = adminUserService.search(query, role, statusFilter, AdminSupport.pageOrDefault(page));
        model.addAttribute("results", results);
        model.addAttribute("accountStatus", accountControl.statusFor(results.getContent().stream().map(User::getId).toList()));
        model.addAttribute("query", query);
        model.addAttribute("role", role);
        model.addAttribute("status", statusFilter);
        model.addAttribute("roles", UserRole.values());
        model.addAttribute("statuses", STATUSES);
        model.addAttribute("active", "users");
        return "admin/users/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','PERM_USERS_READ')")
    public String view(@PathVariable UUID id, @RequestParam(required = false) String tab, Model model) {
        var user = adminUserService.get(id);
        boolean activityTab = "activity".equals(tab);
        model.addAttribute("user", user);
        model.addAttribute("tab", activityTab ? "activity" : "overview");
        model.addAttribute("ownedBusinesses", businessRepository.findByOwnerUserIdAndDeletedAtIsNull(id));
        model.addAttribute("restriction", accountControl.inEffect(id).orElse(null));
        model.addAttribute("restrictions", accountControl.history(id));
        model.addAttribute("logins", accountControl.recentLogins(id, 20));
        model.addAttribute("roles", UserRole.values());
        // Account actions (USER) and staff-role changes (STAFF_ROLE, from here or Community → Moderators).
        model.addAttribute("audit", auditLogRepository
                .findByEntityTypeInAndEntityIdOrderByCreatedAtDesc(List.of("USER", "STAFF_ROLE"), id, PageRequest.of(0, 15)));
        if (activityTab) {
            model.addAttribute("activity", activityService.forUser(user));
        }
        model.addAttribute("active", "users");
        return "admin/users/view";
    }

    // -----------------------------------------------------------------
    // V63 account control — every action needs a reason and is audited
    // -----------------------------------------------------------------

    /** duration: D1 / D7 / D30 / CUSTOM (customEnd is a datetime-local in Asia/Dhaka time). */
    @PostMapping("/{id}/suspend")
    public String suspend(@PathVariable UUID id, @RequestParam(required = false) String duration,
                          @RequestParam(required = false) String customEnd,
                          @RequestParam(required = false) String reason, RedirectAttributes ra) {
        return act(id, ra, () -> {
            Instant endsAt = switch (duration == null ? "" : duration) {
                case "D1" -> Instant.now().plus(Duration.ofDays(1));
                case "D7" -> Instant.now().plus(Duration.ofDays(7));
                case "D30" -> Instant.now().plus(Duration.ofDays(30));
                case "CUSTOM" -> parseLocal(customEnd);
                default -> throw new BadRequestException("Pick a duration.");
            };
            accountControl.suspend(id, endsAt, reason);
            return "User suspended until " + AccountControlService.formatInstant(endsAt) + ". All sessions were revoked.";
        });
    }

    @PostMapping("/{id}/ban")
    public String ban(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        return act(id, ra, () -> {
            accountControl.ban(id, reason);
            return "User banned permanently. All sessions were revoked.";
        });
    }

    @PostMapping("/{id}/lift")
    public String lift(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        return act(id, ra, () -> {
            accountControl.lift(id, reason);
            return "Restriction lifted — the user can log in again.";
        });
    }

    @PostMapping("/{id}/force-logout")
    public String forceLogout(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        return act(id, ra, () -> {
            accountControl.forceLogout(id, reason);
            return "All refresh tokens revoked — the user is signed out everywhere once the current access token expires.";
        });
    }

    /**
     * role = an account type (CONSUMER / BUSINESS_OWNER / ADMIN), MODERATOR (adds the community
     * staff flag, same rules as Community → Moderators) or REMOVE_MODERATOR. Reason required.
     */
    @PostMapping("/{id}/role")
    public String changeRole(@PathVariable UUID id, @RequestParam(required = false) String role,
                             @RequestParam(required = false) String reason, RedirectAttributes ra) {
        return act(id, ra, () -> {
            if (reason == null || reason.isBlank()) {
                throw new BadRequestException("A reason is required.");
            }
            if (MODERATOR.equals(role)) {
                if (adminUserService.get(id).isModerator()) {
                    throw new BadRequestException("This account is already a moderator.");
                }
                moderation.assignModerator(null, id, reason.trim());
                return "Moderator role added — this account can now sign in to the admin panel's Community section.";
            }
            if (REMOVE_MODERATOR.equals(role)) {
                if (!adminUserService.get(id).isModerator()) {
                    throw new BadRequestException("This account isn't a moderator.");
                }
                moderation.removeModerator(id, reason.trim());
                return "Moderator role removed.";
            }
            UserRole accountRole;
            try {
                accountRole = role == null ? null : UserRole.valueOf(role);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown role.");
            }
            accountControl.changeRole(id, accountRole, reason);
            return "Role changed to " + accountRole + ". The user has to sign in again.";
        });
    }

    private String act(UUID id, RedirectAttributes ra, Supplier<String> action) {
        try {
            ra.addFlashAttribute("successMessage", action.get());
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }

    private static Instant parseLocal(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Pick the custom end date and time.");
        }
        try {
            return LocalDateTime.parse(value).atZone(AccountControlService.DISPLAY_ZONE).toInstant();
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Invalid end date.");
        }
    }

    // -----------------------------------------------------------------
    // Account management
    // -----------------------------------------------------------------

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("userForm", new UserForm());
        model.addAttribute("active", "users");
        return "admin/users/form";
    }

    @PostMapping("/new")
    public String create(@Valid @ModelAttribute("userForm") UserForm form, BindingResult bindingResult,
                          Model model, RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("active", "users");
            return "admin/users/form";
        }
        try {
            adminUserService.createAdmin(form);
            redirectAttributes.addFlashAttribute("successMessage", "Admin account created.");
            return "redirect:/admin/users";
        } catch (RuntimeException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("active", "users");
            return "admin/users/form";
        }
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model) {
        model.addAttribute("userForm", UserForm.from(adminUserService.get(id)));
        model.addAttribute("active", "users");
        return "admin/users/edit";
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable UUID id, @ModelAttribute("userForm") UserForm form,
                          Model model, RedirectAttributes redirectAttributes) {
        try {
            adminUserService.updateProfile(id, form);
            redirectAttributes.addFlashAttribute("successMessage", "Profile updated.");
            return "redirect:/admin/users/" + id;
        } catch (RuntimeException ex) {
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("userForm", form);
            model.addAttribute("active", "users");
            return "admin/users/edit";
        }
    }

    @PostMapping("/{id}/reset-password")
    public String resetPassword(@PathVariable UUID id, @RequestParam String newPassword,
                                 RedirectAttributes redirectAttributes) {
        try {
            adminUserService.resetPassword(id, newPassword);
            redirectAttributes.addFlashAttribute("successMessage", "Password reset.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }
}
