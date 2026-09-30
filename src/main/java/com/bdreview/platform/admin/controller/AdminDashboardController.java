package com.bdreview.platform.admin.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.moderation.AuditLogRepository;
import com.bdreview.platform.moderation.ModerationService;
import com.bdreview.platform.review.ReviewRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@PreAuthorize("hasRole('ADMIN')")
public class AdminDashboardController {

    private final ModerationService moderationService;
    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;
    private final ReviewRepository reviewRepository;
    private final AuditLogRepository auditLogRepository;

    public AdminDashboardController(ModerationService moderationService,
                                     UserRepository userRepository,
                                     BusinessRepository businessRepository,
                                     ReviewRepository reviewRepository,
                                     AuditLogRepository auditLogRepository) {
        this.moderationService = moderationService;
        this.userRepository = userRepository;
        this.businessRepository = businessRepository;
        this.reviewRepository = reviewRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping("/admin/dashboard")
    public String dashboard(Model model) {
        model.addAttribute("userCount", userRepository.count());
        model.addAttribute("businessCount", businessRepository.count());
        model.addAttribute("reviewCount", reviewRepository.count());
        model.addAttribute("unclaimedCount", businessRepository.countUnclaimed());
        model.addAttribute("queueCounts", moderationService.counts());
        model.addAttribute("recentAuditLogs",
                auditLogRepository.findAll(PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"))));
        model.addAttribute("active", "dashboard");
        return "admin/dashboard";
    }
}
