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

    public AdminReviewController(AdminReviewService adminReviewService,
                                  ModerationService moderationService,
                                  FakeReviewSignalRepository fakeReviewSignalRepository,
                                  BusinessRepository businessRepository,
                                  UserRepository userRepository) {
        this.adminReviewService = adminReviewService;
        this.moderationService = moderationService;
        this.fakeReviewSignalRepository = fakeReviewSignalRepository;
        this.businessRepository = businessRepository;
        this.userRepository = userRepository;
    }

    @GetMapping
    public String list(@RequestParam(required = false) VisibilityStatus status,
                        @RequestParam(required = false) Integer page,
                        Model model) {
        model.addAttribute("results", adminReviewService.search(status, AdminSupport.pageOrDefault(page)));
        model.addAttribute("status", status);
        model.addAttribute("statuses", VisibilityStatus.values());
        model.addAttribute("active", "reviews");
        return "admin/reviews/list";
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
