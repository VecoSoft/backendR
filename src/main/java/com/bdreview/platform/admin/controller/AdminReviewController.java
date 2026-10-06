package com.bdreview.platform.admin.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import com.bdreview.platform.admin.service.AdminReviewService;
import com.bdreview.platform.admin.support.AdminSupport;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.fakereview.FakeReviewSignalRepository;
import com.bdreview.platform.moderation.ModerationService;
import com.bdreview.platform.review.Review;
import com.bdreview.platform.review.VisibilityStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/admin/reviews")
public class AdminReviewController {

    private final AdminReviewService adminReviewService;
    private final ModerationService moderationService;
    private final FakeReviewSignalRepository fakeReviewSignalRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final com.bdreview.platform.adminconfig.AdminConfigService adminConfig;

    public AdminReviewController(AdminReviewService adminReviewService,
                                  ModerationService moderationService,
                                  FakeReviewSignalRepository fakeReviewSignalRepository,
                                  BusinessRepository businessRepository,
                                  UserRepository userRepository,
                                  com.bdreview.platform.adminconfig.AdminConfigService adminConfig) {
        this.adminConfig = adminConfig;
        this.adminReviewService = adminReviewService;
        this.moderationService = moderationService;
        this.fakeReviewSignalRepository = fakeReviewSignalRepository;
        this.businessRepository = businessRepository;
        this.userRepository = userRepository;
    }

    /** V65: filters by suspicion score range, business, user and date; rows support bulk actions. */
    @GetMapping
    public String list(@RequestParam(required = false) VisibilityStatus status,
                        @RequestParam(required = false) Integer minScore,
                        @RequestParam(required = false) Integer maxScore,
                        @RequestParam(required = false) String business,
                        @RequestParam(required = false) String user,
                        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
                        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
                        @RequestParam(required = false) Integer page,
                        Model model) {
        var filter = new AdminReviewService.Filter(status, minScore, maxScore, business, user, from, to);
        model.addAttribute("results", adminReviewService.filter(filter, AdminSupport.pageOrDefault(page)));
        model.addAttribute("f", filter);
        model.addAttribute("status", status);
        model.addAttribute("statuses", VisibilityStatus.values());
        model.addAttribute("active", "reviews");
        return "admin/reviews/list";
    }

    @PostMapping("/bulk")
    public String bulk(@RequestParam(required = false) java.util.List<UUID> ids, @RequestParam String action,
                       @RequestParam(required = false) String reason, @RequestParam(required = false) String back,
                       RedirectAttributes redirectAttributes) {
        try {
            VisibilityStatus target = "hide".equals(action) ? VisibilityStatus.HIDDEN
                    : "not-recommended".equals(action) ? VisibilityStatus.NOT_RECOMMENDED : null;
            if (target == null) {
                throw new com.bdreview.platform.common.BadRequestException("Unknown action.");
            }
            int changed = adminReviewService.bulkSetVisibility(ids, target, reason);
            redirectAttributes.addFlashAttribute("successMessage", changed + " review(s) set to " + target + ".");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return back != null && back.startsWith("/admin/reviews") ? "redirect:" + back : "redirect:/admin/reviews";
    }

    /** V65 Moderation → Review settings. */
    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("policy", adminConfig.reviewPolicy());
        model.addAttribute("defaults", new com.bdreview.platform.adminconfig.ReviewPolicyConfig());
        model.addAttribute("active", "review-settings");
        return "admin/reviews/settings";
    }

    @PostMapping("/settings")
    public String saveSettings(@RequestParam int minLength, @RequestParam int editWindowHours,
                               @RequestParam int maxReviewsPerUserPerDay, @RequestParam int notRecommendedThreshold,
                               @RequestParam int hiddenThreshold,
                               @RequestParam(defaultValue = "false") boolean competitorRuleEnabled,
                               @RequestParam(required = false) String reason, RedirectAttributes redirectAttributes) {
        try {
            var policy = adminConfig.reviewPolicy();
            policy.setMinLength(minLength);
            policy.setEditWindowHours(editWindowHours);
            policy.setMaxReviewsPerUserPerDay(maxReviewsPerUserPerDay);
            policy.setNotRecommendedThreshold(notRecommendedThreshold);
            policy.setHiddenThreshold(hiddenThreshold);
            policy.setCompetitorRuleEnabled(competitorRuleEnabled);
            adminConfig.saveReviewPolicy(policy, reason);
            redirectAttributes.addFlashAttribute("successMessage", "Review settings saved — they apply to the next review.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/reviews/settings";
    }

    @GetMapping("/{id}")
    public String view(@PathVariable UUID id, Model model) {
        Review review = adminReviewService.get(id);
        model.addAttribute("review", review);
        model.addAttribute("signals", fakeReviewSignalRepository.findByReviewId(id));
        businessRepository.findById(review.getBusinessId()).ifPresent(b -> model.addAttribute("business", b));
        userRepository.findById(review.getUserId()).ifPresent(u -> model.addAttribute("author", u));
        model.addAttribute("active", "reviews");
        return "admin/reviews/view";
    }

    @PostMapping("/{id}/visibility")
    public String setVisibility(@PathVariable UUID id, @RequestParam VisibilityStatus status,
                                 @RequestParam(required = false) String notes,
                                 Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            moderationService.resolveFlaggedReview(id, status, AdminSupport.currentAdminId(authentication), notes);
            redirectAttributes.addFlashAttribute("successMessage", "Review visibility set to " + status + ".");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/reviews/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, @RequestParam(required = false) String notes,
                          Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            adminReviewService.delete(id, AdminSupport.currentAdminId(authentication), notes);
            redirectAttributes.addFlashAttribute("successMessage", "Review removed.");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/admin/reviews";
    }
}
