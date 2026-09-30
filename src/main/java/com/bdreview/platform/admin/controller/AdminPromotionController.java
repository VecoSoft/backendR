package com.bdreview.platform.admin.controller;

import com.bdreview.platform.business.Area;
import com.bdreview.platform.business.AreaRepository;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.community.settings.CommunitySettings;
import com.bdreview.platform.promo.*;
import com.bdreview.platform.promo.PromoEnums.BoostStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;

/**
 * Admin panel → Promotions (V58), next to the Community section. MODERATOR or ADMIN: the review
 * queues (business posts, boosts awaiting review) and live-boost pause/end. ADMIN only: settings,
 * templates, packages, payment verification, refunds, re-targeting, restrictions and revenue —
 * enforced three times (AdminSecurityConfig URL rules, @PreAuthorize here, and the services).
 * Every write goes through a service that audit-logs it.
 */
@Controller
@RequestMapping("/admin/promotions")
@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
public class AdminPromotionController {

    private final PromoAdminService adminService;
    private final BusinessPostService businessPostService;
    private final BoostService boostService;
    private final AreaRepository areaRepository;
    private final BusinessRepository businessRepository;

    public AdminPromotionController(PromoAdminService adminService, BusinessPostService businessPostService,
                                    BoostService boostService, AreaRepository areaRepository,
                                    BusinessRepository businessRepository) {
        this.adminService = adminService;
        this.businessPostService = businessPostService;
        this.boostService = boostService;
        this.areaRepository = areaRepository;
        this.businessRepository = businessRepository;
    }

    @ModelAttribute("frontendUrl")
    public String frontendUrl(@Value("${app.frontend-url:http://localhost:3000}") String url) {
        return url;
    }

    // -----------------------------------------------------------------
    // Queues (MODERATOR / ADMIN)
    // -----------------------------------------------------------------

    @GetMapping
    public String queues(Model model) {
        model.addAttribute("pendingPosts", adminService.pendingPosts());
        model.addAttribute("pendingPayment", boostService.adminList(EnumSet.of(BoostStatus.PENDING_PAYMENT)).stream()
                .filter(b -> b.paymentRef() != null).toList());
        model.addAttribute("awaitingPayment", boostService.adminList(EnumSet.of(BoostStatus.PENDING_PAYMENT)).stream()
                .filter(b -> b.paymentRef() == null).count());
        model.addAttribute("pendingReview", boostService.adminList(EnumSet.of(BoostStatus.PENDING_REVIEW)));
        model.addAttribute("active", "p-queues");
        return "admin/promotions/queues";
    }

    @PostMapping("/posts/{postId}/approve")
    public String approvePost(@PathVariable UUID postId, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        run(ra, () -> {
            businessPostService.approve(postId, reason);
            return "Business post approved — it's live in the feed.";
        });
        return "redirect:/admin/promotions";
    }

    @PostMapping("/posts/{postId}/reject")
    public String rejectPost(@PathVariable UUID postId, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        run(ra, () -> {
            businessPostService.reject(postId, reason);
            return "Business post rejected — the owner sees your reason.";
        });
        return "redirect:/admin/promotions";
    }

    @PostMapping("/boosts/{id}/verify-payment")
    @PreAuthorize("hasRole('ADMIN')")
    public String verifyPayment(@PathVariable UUID id, @RequestParam(required = false) BigDecimal paidAmount,
                                @RequestParam(required = false) String note, RedirectAttributes ra) {
        run(ra, () -> "Payment verified — boost is now " + boostService.verifyPayment(id, paidAmount, note).status() + ".");
        return "redirect:/admin/promotions";
    }

    @PostMapping("/boosts/{id}/reject-payment")
    @PreAuthorize("hasRole('ADMIN')")
    public String rejectPayment(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        run(ra, () -> {
            boostService.rejectPayment(id, reason);
            return "Payment rejected.";
        });
        return "redirect:/admin/promotions";
    }

    @PostMapping("/boosts/{id}/approve")
    public String approveBoost(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        run(ra, () -> {
            boostService.approve(id, reason);
            return "Boost approved — it's live.";
        });
        return "redirect:/admin/promotions";
    }

    @PostMapping("/boosts/{id}/reject")
    public String rejectBoost(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        run(ra, () -> {
            boostService.reject(id, reason);
            return "Boost rejected.";
        });
        return "redirect:/admin/promotions";
    }

    // -----------------------------------------------------------------
    // Live boosts
    // -----------------------------------------------------------------

    @GetMapping("/boosts")
    public String boosts(@RequestParam(required = false) String status, Model model) {
        EnumSet<BoostStatus> statuses = status == null || status.isBlank()
                ? EnumSet.of(BoostStatus.ACTIVE, BoostStatus.PAUSED)
                : "ALL".equals(status) ? EnumSet.allOf(BoostStatus.class) : EnumSet.of(BoostStatus.valueOf(status));
        model.addAttribute("boosts", boostService.adminList(statuses));
        model.addAttribute("status", status);
        model.addAttribute("areas", areaRepository.findAllWithCity());
        model.addAttribute("active", "p-boosts");
        return "admin/promotions/boosts";
    }

    @PostMapping("/boosts/{id}/action")
    public String boostAction(@PathVariable UUID id, @RequestParam String action, @RequestParam(required = false) String reason,
                              RedirectAttributes ra) {
        run(ra, () -> switch (action) {
            case "pause" -> "Paused: " + boostService.adminPause(id, true, reason).status();
            case "resume" -> "Resumed: " + boostService.adminPause(id, false, reason).status();
            case "end" -> "Ended: " + boostService.end(id, reason).status();
            case "refund" -> "Refunded: " + boostService.refund(id, reason).status();
            default -> throw new IllegalArgumentException("Unknown action " + action);
        });
        return "redirect:/admin/promotions/boosts";
    }

    @PostMapping("/boosts/{id}/targeting")
    @PreAuthorize("hasRole('ADMIN')")
    public String retarget(@PathVariable UUID id, @RequestParam(required = false) List<UUID> areaIds,
                           @RequestParam(required = false) Integer radiusKm, @RequestParam(required = false) String reason,
                           RedirectAttributes ra) {
        run(ra, () -> {
            boostService.changeTargeting(id, new BoostService.TargetingRequest(areaIds, areaIds == null || areaIds.isEmpty() ? radiusKm : null), reason);
            return "Targeting updated.";
        });
        return "redirect:/admin/promotions/boosts";
    }

    // -----------------------------------------------------------------
    // Settings (ADMIN)
    // -----------------------------------------------------------------

    @GetMapping("/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public String settings(Model model) {
        model.addAttribute("promo", adminService.settings());
        model.addAttribute("categoryLabels", PromotionContentRules.CATEGORY_LABELS);
        model.addAttribute("active", "p-settings");
        return "admin/promotions/settings";
    }

    @PostMapping("/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public String saveSettings(@RequestParam Map<String, String> form,
                               @RequestParam(required = false) List<String> bannedCategories, RedirectAttributes ra) {
        run(ra, () -> {
            CommunitySettings.Promotions p = adminService.settings();
            p.setBusinessPostsEnabled(form.containsKey("businessPostsEnabled"));
            p.setBoostsEnabled(form.containsKey("boostsEnabled"));
            p.setRequireApprovalForBusinessPosts(form.containsKey("requireApprovalForBusinessPosts"));
            p.setSponsoredInSearch(form.containsKey("sponsoredInSearch"));
            p.setFeaturedNearbyEnabled(form.containsKey("featuredNearbyEnabled"));
            p.setCaptionAiEnabled(form.containsKey("captionAiEnabled"));
            p.setBoostRequiresReview(form.containsKey("boostRequiresReview"));
            p.setBusinessPostsPerWeek(intOf(form, "businessPostsPerWeek", p.getBusinessPostsPerWeek()));
            p.setMinBodyLength(intOf(form, "minBodyLength", p.getMinBodyLength()));
            p.setSponsoredFeedRatio(intOf(form, "sponsoredFeedRatio", p.getSponsoredFeedRatio()));
            p.setFrequencyCapPerDay(intOf(form, "frequencyCapPerDay", p.getFrequencyCapPerDay()));
            p.setCaptionsPerBusinessPerDay(intOf(form, "captionsPerBusinessPerDay", p.getCaptionsPerBusinessPerDay()));
            p.setBannedCategories(bannedCategories == null ? new ArrayList<>() : new ArrayList<>(bannedCategories));
            p.setExtraBannedKeywords(new ArrayList<>(Arrays.stream(form.getOrDefault("extraBannedKeywords", "").split("[\\n,]"))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList()));
            p.setBkashNumber(form.getOrDefault("bkashNumber", "").trim());
            p.setNagadNumber(form.getOrDefault("nagadNumber", "").trim());
            adminService.saveSettings(p, form.get("reason"));
            return "Promotion settings saved — they apply to the next request.";
        });
        return "redirect:/admin/promotions/settings";
    }

    // -----------------------------------------------------------------
    // Templates (ADMIN)
    // -----------------------------------------------------------------

    @GetMapping("/templates")
    @PreAuthorize("hasRole('ADMIN')")
    public String templates(Model model) {
        model.addAttribute("templates", adminService.templates());
        model.addAttribute("active", "p-templates");
        return "admin/promotions/templates";
    }

    @PostMapping("/templates/{key}/active")
    @PreAuthorize("hasRole('ADMIN')")
    public String templateActive(@PathVariable String key, @RequestParam boolean value, RedirectAttributes ra) {
        run(ra, () -> {
            adminService.setTemplateActive(key, value);
            return key + (value ? " enabled." : " disabled.");
        });
        return "redirect:/admin/promotions/templates";
    }

    @PostMapping("/templates/move")
    @PreAuthorize("hasRole('ADMIN')")
    public String moveTemplate(@RequestParam String key, @RequestParam String direction, RedirectAttributes ra) {
        run(ra, () -> {
            List<String> keys = new ArrayList<>(adminService.templates().stream().map(PromoTemplate::getKey).toList());
            int i = keys.indexOf(key);
            int j = "up".equals(direction) ? i - 1 : i + 1;
            if (i < 0 || j < 0 || j >= keys.size()) {
                return "Nothing to move.";
            }
            Collections.swap(keys, i, j);
            adminService.reorderTemplates(keys);
            return "Order updated.";
        });
        return "redirect:/admin/promotions/templates";
    }

    // -----------------------------------------------------------------
    // Packages (ADMIN)
    // -----------------------------------------------------------------

    @GetMapping("/packages")
    @PreAuthorize("hasRole('ADMIN')")
    public String packages(Model model) {
        model.addAttribute("packages", adminService.packages());
        model.addAttribute("active", "p-packages");
        return "admin/promotions/packages";
    }

    @PostMapping("/packages")
    @PreAuthorize("hasRole('ADMIN')")
    public String savePackage(@RequestParam(required = false) UUID id, @RequestParam String name, @RequestParam BigDecimal priceBdt,
                              @RequestParam int estImpressions, @RequestParam int durationDays, @RequestParam int maxRadiusKm,
                              @RequestParam(defaultValue = "false") boolean active, @RequestParam(defaultValue = "0") int sortOrder,
                              RedirectAttributes ra) {
        run(ra, () -> {
            if (name.isBlank() || priceBdt.signum() <= 0 || estImpressions < 1 || durationDays < 1 || durationDays > 90
                    || maxRadiusKm < 1 || maxRadiusKm > 50) {
                throw new IllegalArgumentException("Check the values: price > 0, impressions ≥ 1, 1–90 days, 1–50 km.");
            }
            adminService.savePackage(id, new PromoAdminService.PackageRequest(name, priceBdt, estImpressions, durationDays,
                    maxRadiusKm, active, sortOrder));
            return id == null ? "Package created." : "Package updated.";
        });
        return "redirect:/admin/promotions/packages";
    }

    @PostMapping("/packages/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String deletePackage(@PathVariable UUID id, RedirectAttributes ra) {
        run(ra, () -> {
            adminService.deletePackage(id);
            return "Package removed (deactivated if it has boosts).";
        });
        return "redirect:/admin/promotions/packages";
    }

    // -----------------------------------------------------------------
    // Restrictions (ADMIN)
    // -----------------------------------------------------------------

    @GetMapping("/restrictions")
    @PreAuthorize("hasRole('ADMIN')")
    public String restrictions(@RequestParam(required = false) String q, Model model) {
        model.addAttribute("restrictions", adminService.activeRestrictions());
        model.addAttribute("q", q);
        model.addAttribute("matches", q == null || q.isBlank() ? List.of()
                : businessRepository.adminSearch(q.trim(), null, null, false, false,
                        org.springframework.data.domain.PageRequest.of(0, 10)).getContent());
        model.addAttribute("active", "p-restrictions");
        return "admin/promotions/restrictions";
    }

    @PostMapping("/restrictions")
    @PreAuthorize("hasRole('ADMIN')")
    public String restrict(@RequestParam UUID businessId, @RequestParam(required = false) Integer days,
                           @RequestParam(required = false) String reason, RedirectAttributes ra) {
        run(ra, () -> {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("A reason is required.");
            }
            adminService.restrict(new PromoAdminService.RestrictionRequest(businessId, days, reason));
            return "Promotion rights suspended.";
        });
        return "redirect:/admin/promotions/restrictions";
    }

    @PostMapping("/restrictions/{id}/lift")
    @PreAuthorize("hasRole('ADMIN')")
    public String lift(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        run(ra, () -> {
            adminService.lift(id, reason);
            return "Promotion rights restored.";
        });
        return "redirect:/admin/promotions/restrictions";
    }

    // -----------------------------------------------------------------
    // Revenue (ADMIN)
    // -----------------------------------------------------------------

    @GetMapping("/revenue")
    @PreAuthorize("hasRole('ADMIN')")
    public String revenue(@RequestParam(defaultValue = "30") int days, Model model) {
        List<PromoAdminService.RevenueRow> rows = adminService.revenue(days);
        Map<String, BigDecimal> byPackage = new TreeMap<>();
        Map<String, BigDecimal> byWeek = new TreeMap<>(Comparator.reverseOrder());
        BigDecimal total = BigDecimal.ZERO;
        for (PromoAdminService.RevenueRow r : rows) {
            byPackage.merge(r.packageName(), r.amount(), BigDecimal::add);
            var week = r.day().with(java.time.DayOfWeek.SATURDAY).minusWeeks(r.day().getDayOfWeek() == java.time.DayOfWeek.SATURDAY ? 0 : 1);
            byWeek.merge(week.toString(), r.amount(), BigDecimal::add);
            total = total.add(r.amount());
        }
        model.addAttribute("rows", rows);
        model.addAttribute("byPackage", byPackage);
        model.addAttribute("byWeek", byWeek);
        model.addAttribute("total", total);
        model.addAttribute("days", days);
        model.addAttribute("active", "p-revenue");
        return "admin/promotions/revenue";
    }

    // -----------------------------------------------------------------

    private static int intOf(Map<String, String> form, String key, int fallback) {
        try {
            return Integer.parseInt(form.getOrDefault(key, "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void run(RedirectAttributes ra, Supplier<String> action) {
        try {
            ra.addFlashAttribute("successMessage", action.get());
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("errorMessage", ex.getMessage());
        }
    }

    /** Area label helper for templates ("Mirpur, Dhaka"). */
    public static String areaLabel(Area a) {
        return a.getName() + ", " + a.getCity().getName();
    }
}
