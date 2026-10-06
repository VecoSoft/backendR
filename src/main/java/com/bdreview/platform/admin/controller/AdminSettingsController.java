package com.bdreview.platform.admin.controller;

import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.features.FeatureFlagService;
import com.bdreview.platform.features.PlatformFeature;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * System → Settings (V63): platform feature flags stored in the database, overriding the
 * deployment defaults. Exposed read-only to the app through GET /api/v1/community/settings
 * ({@code features}). ADMIN only; every change needs a reason and is audited.
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/admin/settings")
public class AdminSettingsController {

    private final FeatureFlagService flags;
    private final UserRepository userRepository;

    public AdminSettingsController(FeatureFlagService flags, UserRepository userRepository) {
        this.flags = flags;
        this.userRepository = userRepository;
    }

    @GetMapping
    public String index(Model model) {
        var views = flags.all();
        Map<UUID, User> editors = userRepository.findAllById(views.stream().map(FeatureFlagService.FlagView::updatedBy)
                        .filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        model.addAttribute("flags", views);
        model.addAttribute("editors", editors);
        model.addAttribute("defaultMaintenanceMessage", FeatureFlagService.DEFAULT_MAINTENANCE_MESSAGE);
        model.addAttribute("active", "settings");
        return "admin/settings/index";
    }

    /** mode = on / off / default (clear the override). */
    @PostMapping("/{flag}")
    public String update(@PathVariable String flag, @RequestParam String mode,
                         @RequestParam(required = false) String message,
                         @RequestParam(required = false) String reason,
                         RedirectAttributes ra) {
        try {
            PlatformFeature feature;
            try {
                feature = PlatformFeature.valueOf(flag);
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown setting.");
            }
            Boolean override = switch (mode) {
                case "on" -> Boolean.TRUE;
                case "off" -> Boolean.FALSE;
                case "default" -> null;
                default -> throw new BadRequestException("Unknown mode.");
            };
            flags.set(feature, override, message, reason);
            ra.addFlashAttribute("successMessage", feature.label() + " is now "
                    + (flags.isEnabled(feature) ? "ON" : "OFF") + (override == null ? " (deployment default)." : "."));
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/settings";
    }
}
