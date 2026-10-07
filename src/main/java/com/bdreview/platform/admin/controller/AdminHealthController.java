package com.bdreview.platform.admin.controller;

import com.bdreview.platform.health.HealthService;
import com.bdreview.platform.health.RecentErrors;
import com.bdreview.platform.retention.DataRetentionSettings;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.Map;

/**
 * System → Health (V67): status cards, largest tables, scheduled jobs (with "Run now"), data
 * retention periods (V69) and recent server errors. SUPER_ADMIN.
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/admin/health")
public class AdminHealthController {

    private final HealthService health;
    private final RecentErrors recentErrors;
    private final DataRetentionSettings retention;

    public AdminHealthController(HealthService health, RecentErrors recentErrors, DataRetentionSettings retention) {
        this.health = health;
        this.recentErrors = recentErrors;
        this.retention = retention;
    }

    @GetMapping
    public String index(@RequestParam(required = false) String job, Model model) {
        model.addAttribute("checks", health.checks());
        model.addAttribute("tables", health.largestTables());
        model.addAttribute("jobs", health.jobs());
        model.addAttribute("retentionPeriods", retention.periods());
        model.addAttribute("retentionEffective", retention.effectiveByKey());
        var overrides = retention.overrides();
        Map<String, Integer> overrideByKey = new HashMap<>();
        retention.periods().forEach(p -> overrideByKey.put(p.key(), p.getter().apply(overrides)));
        model.addAttribute("retentionOverrides", overrideByKey);
        model.addAttribute("protectedAuditDays", DataRetentionSettings.PROTECTED_AUDIT_MIN_DAYS);
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

    /** Saves retention overrides; a blank field goes back to the deployment default. */
    @PostMapping("/retention")
    public String saveRetention(@RequestParam Map<String, String> form, RedirectAttributes ra) {
        try {
            Map<String, Integer> values = new HashMap<>();
            for (DataRetentionSettings.Period p : retention.periods()) {
                String raw = form.get(p.key());
                if (raw != null && !raw.isBlank()) {
                    try {
                        values.put(p.key(), Integer.parseInt(raw.trim()));
                    } catch (NumberFormatException e) {
                        throw new com.bdreview.platform.common.BadRequestException(p.label() + ": enter a whole number of days.");
                    }
                }
            }
            retention.save(values, form.get("reason"));
            ra.addFlashAttribute("successMessage", "Retention periods saved — the next retention run uses them.");
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/health#retention";
    }
}
