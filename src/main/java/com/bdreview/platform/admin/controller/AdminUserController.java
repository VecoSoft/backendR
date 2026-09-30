package com.bdreview.platform.admin.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import com.bdreview.platform.admin.form.UserForm;
import com.bdreview.platform.admin.service.AdminUserService;
import com.bdreview.platform.admin.support.AdminSupport;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.business.BusinessRepository;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final BusinessRepository businessRepository;

    public AdminUserController(AdminUserService adminUserService, BusinessRepository businessRepository) {
        this.adminUserService = adminUserService;
        this.businessRepository = businessRepository;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String query,
                        @RequestParam(required = false) UserRole role,
                        @RequestParam(required = false) Integer page,
                        Model model) {
        model.addAttribute("results", adminUserService.search(query, role, AdminSupport.pageOrDefault(page)));
        model.addAttribute("query", query);
        model.addAttribute("role", role);
        model.addAttribute("roles", UserRole.values());
        model.addAttribute("active", "users");
        return "admin/users/list";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        var user = adminUserService.get(id);
        model.addAttribute("user", user);
        model.addAttribute("ownedBusinesses", businessRepository.findByOwnerUserIdAndDeletedAtIsNull(id));
        model.addAttribute("active", "users");
        return "admin/users/view";
    }

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
