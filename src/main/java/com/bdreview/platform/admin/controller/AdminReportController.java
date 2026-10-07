package com.bdreview.platform.admin.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import com.bdreview.platform.admin.support.AdminSupport;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.report.Report;
import com.bdreview.platform.report.ReportService;
import com.bdreview.platform.report.ReportStatus;
import com.bdreview.platform.report.ReportTargetType;
import com.bdreview.platform.report.ReporterCredibility;
import com.bdreview.platform.review.ReviewRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Controller
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','PERM_CONTENT')")
@RequestMapping("/admin/reports")
public class AdminReportController {

    private final ReportService reportService;
    private final ReviewRepository reviewRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    public AdminReportController(ReportService reportService,
                                  ReviewRepository reviewRepository,
                                  BusinessRepository businessRepository,
                                  UserRepository userRepository) {
        this.reportService = reportService;
        this.reviewRepository = reviewRepository;
        this.businessRepository = businessRepository;
        this.userRepository = userRepository;
    }

    @GetMapping
    public String list(@RequestParam(required = false) Integer page, Model model) {
        var results = reportService.queue(PageRequest.of(AdminSupport.pageOrDefault(page), 20));

        // Small preview of each report's target (review snippet / listing name) for the queue table.
        Map<UUID, String> targetPreviews = new HashMap<>();
        Map<UUID, ReporterCredibility> reporterStats = new HashMap<>();
        Map<UUID, Long> targetReportCounts = new HashMap<>();
        Map<UUID, String> reporterNames = new HashMap<>();
        for (Report report : results.getContent()) {
            if (report.getTargetType() == ReportTargetType.REVIEW) {
                reviewRepository.findByIdAndDeletedAtIsNull(report.getTargetId())
                        .ifPresent(r -> targetPreviews.put(report.getId(),
                                r.getContent() == null ? "(no text)" : truncate(r.getContent())));
            } else {
                businessRepository.findById(report.getTargetId())
                        .ifPresent(b -> targetPreviews.put(report.getId(), b.getName()));
            }
            reporterStats.computeIfAbsent(report.getReporterUserId(), reportService::reporterCredibility);
            targetReportCounts.put(report.getId(),
                    reportService.reportCountForTarget(report.getTargetType(), report.getTargetId()));
            reporterNames.computeIfAbsent(report.getReporterUserId(),
                    id -> userRepository.findById(id).map(u -> u.getName()).orElse(null));
        }

        model.addAttribute("results", results);
        model.addAttribute("targetPreviews", targetPreviews);
        model.addAttribute("reporterStats", reporterStats);
        model.addAttribute("targetReportCounts", targetReportCounts);
        model.addAttribute("reporterNames", reporterNames);
        model.addAttribute("active", "reports");
        return "admin/reports/list";
    }

    @PostMapping("/{id}/resolve")
    public String resolve(@PathVariable UUID id, @RequestParam ReportStatus outcome,
                           @RequestParam(required = false) String resolutionNote,
                           RedirectAttributes redirectAttributes) {
        try {
            reportService.resolve(id, outcome, resolutionNote);
            redirectAttributes.addFlashAttribute("successMessage", "Report marked " + outcome.name() + ".");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/reports";
    }

    private static String truncate(String text) {
        return text.length() > 140 ? text.substring(0, 140) + "…" : text;
    }
}
