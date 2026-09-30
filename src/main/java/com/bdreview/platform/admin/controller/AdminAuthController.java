package com.bdreview.platform.admin.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AdminAuthController {

    /** Post-login landing: admins go to the main dashboard, community moderators to their section. */
    @GetMapping("/admin")
    public String root(org.springframework.security.core.Authentication authentication) {
        boolean admin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return admin ? "redirect:/admin/dashboard" : "redirect:/admin/community";
    }

    @GetMapping("/admin/login")
    public String loginPage(@RequestParam(required = false) String error,
                             @RequestParam(required = false) String logout,
                             @RequestParam(required = false) String denied,
                             org.springframework.ui.Model model) {
        if (error != null) {
            model.addAttribute("errorMessage", "Invalid phone number or password.");
        }
        if (logout != null) {
            model.addAttribute("infoMessage", "You have been logged out.");
        }
        if (denied != null) {
            model.addAttribute("errorMessage", "You do not have permission to access that page.");
        }
        return "admin/login";
    }
}
