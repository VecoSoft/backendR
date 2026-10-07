package com.bdreview.platform.admin.controller;

import com.bdreview.platform.health.HealthService;
import com.bdreview.platform.health.RecentErrors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** System → Health (V67): status cards, scheduled jobs (with "Run now"), recent server errors. SUPER_ADMIN. */
@Controller
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/admin/health")
public class AdminHealthController {

    private final HealthService health;
    private final RecentErrors recentErrors;

    public AdminHealthController(HealthService health, RecentErrors recentErrors) {
        this.health = health;
        this.recentErrors = recentErrors;
    }

    @GetMapping
    public String index(@RequestParam(required = false) String job, Model model) {
        model.addAttribute("checks", health.checks());
        model.addAttribute("jobs", health.jobs());
        model.addAttribute("errors", recentErrors.latest());
        if (job != null && !job.isBlank()) {
            model.addAttribute("jobName", job);
            model.addAttribute("runs", health.runs(job, 50));
        }
        model.addAttribute("active", "health");
        return "admin/health/index";
    }

    @PostMapping("/jobs/{name}/run")
    public String runNow(@PathVariable String name, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        try {
            health.runNow(name, reason);
            ra.addFlashAttribute("successMessage", "Ran " + name + " — see its latest run below.");
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/health?job=" + name;
    }
}
