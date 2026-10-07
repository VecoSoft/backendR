package com.bdreview.platform.admin.controller;

import com.bdreview.platform.content.ContentPageService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/** System → Content (V67): Terms, Privacy, FAQ, Help in English and Bangla with version history. SUPER_ADMIN. */
@Controller
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/admin/content")
public class AdminContentController {

    private final ContentPageService pages;

    public AdminContentController(ContentPageService pages) {
        this.pages = pages;
    }

    @GetMapping
    public String index(Model model) {
        model.addAttribute("pages", pages.overview());
        model.addAttribute("active", "content");
        return "admin/content/index";
    }

    @GetMapping("/{slug}/{locale}")
    public String edit(@PathVariable String slug, @PathVariable String locale, @RequestParam(required = false) UUID version, Model model) {
        model.addAttribute("page", pages.get(slug, locale));
        model.addAttribute("history", pages.history(slug, locale));
        if (version != null) {
            model.addAttribute("viewing", pages.version(version));
        }
        model.addAttribute("slug", slug);
        model.addAttribute("locale", locale);
        model.addAttribute("active", "content");
        return "admin/content/edit";
    }

    @PostMapping("/{slug}/{locale}")
    public String save(@PathVariable String slug, @PathVariable String locale, @RequestParam String title,
                       @RequestParam String bodyMd, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        try {
            var p = pages.save(slug, locale, title, bodyMd, reason);
            ra.addFlashAttribute("successMessage", "Saved as version " + p.version() + " — live on the public page now.");
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/content/" + slug + "/" + locale;
    }

    @PostMapping("/versions/{id}/restore")
    public String restore(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        var v = pages.version(id);
        try {
            var p = pages.restore(id, reason);
            ra.addFlashAttribute("successMessage", "Restored — saved as version " + p.version() + ".");
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/content/" + v.get("slug") + "/" + v.get("locale");
    }
}
