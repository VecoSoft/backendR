package com.bdreview.platform.promo;

import com.bdreview.platform.community.settings.CommunitySettings;
import com.bdreview.platform.promo.PromoEnums.BoostStatus;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * JSON admin API for promotions (V58) — the same operations as the Thymeleaf pages under
 * /admin/promotions, for scripts and tests. URL-level: staff only (SecurityConfig
 * /api/v1/admin/**). Method-level: settings, templates, packages, payments, refunds, re-targeting,
 * restrictions and revenue are ADMIN; post and boost review, pause and end are MODERATOR or ADMIN.
 * The services re-check roles as well.
 */
@RestController
@RequestMapping("/api/v1/admin/promotions")
@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")
public class PromoAdminApiController {

    public record ReasonRequest(String reason) {
    }

    public record VerifyRequest(BigDecimal paidAmount, String note) {
    }

    public record SettingsRequest(@Valid CommunitySettings.Promotions promotions, String reason) {
    }

    private final PromoAdminService adminService;
    private final BusinessPostService businessPostService;
    private final BoostService boostService;

    public PromoAdminApiController(PromoAdminService adminService, BusinessPostService businessPostService, BoostService boostService) {
        this.adminService = adminService;
        this.businessPostService = businessPostService;
        this.boostService = boostService;
    }

    @GetMapping("/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public CommunitySettings.Promotions settings() {
        return adminService.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public CommunitySettings.Promotions saveSettings(@RequestBody SettingsRequest req) {
        return adminService.saveSettings(req.promotions(), req.reason());
    }

    @GetMapping("/packages")
    @PreAuthorize("hasRole('ADMIN')")
    public List<BoostPackage> packages() {
        return adminService.packages();
    }

    @PostMapping("/packages")
    @PreAuthorize("hasRole('ADMIN')")
    public BoostPackage createPackage(@Valid @RequestBody PromoAdminService.PackageRequest req) {
        return adminService.savePackage(null, req);
    }

    @PutMapping("/packages/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public BoostPackage updatePackage(@PathVariable UUID id, @Valid @RequestBody PromoAdminService.PackageRequest req) {
        return adminService.savePackage(id, req);
    }

    @PostMapping("/templates/{key}/active")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> templateActive(@PathVariable String key, @RequestParam boolean value) {
        adminService.setTemplateActive(key, value);
        return ResponseEntity.noContent().build();
    }

    // ---- Queues ----

    @GetMapping("/posts/pending")
    public List<PromoAdminService.PendingPost> pendingPosts() {
        return adminService.pendingPosts();
    }

    @PostMapping("/posts/{postId}/approve")
    public ResponseEntity<Void> approvePost(@PathVariable UUID postId, @RequestBody(required = false) ReasonRequest req) {
        businessPostService.approve(postId, req == null ? null : req.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/posts/{postId}/reject")
    public ResponseEntity<Void> rejectPost(@PathVariable UUID postId, @RequestBody ReasonRequest req) {
        businessPostService.reject(postId, req.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/boosts")
    public List<BoostService.BoostView> boosts(@RequestParam(required = false) List<BoostStatus> status) {
        return boostService.adminList(status == null || status.isEmpty() ? EnumSet.allOf(BoostStatus.class) : EnumSet.copyOf(status));
    }

    @PostMapping("/boosts/{id}/verify-payment")
    @PreAuthorize("hasRole('ADMIN')")
    public BoostService.BoostView verify(@PathVariable UUID id, @RequestBody(required = false) VerifyRequest req) {
        return boostService.verifyPayment(id, req == null ? null : req.paidAmount(), req == null ? null : req.note());
    }

    @PostMapping("/boosts/{id}/reject-payment")
    @PreAuthorize("hasRole('ADMIN')")
    public BoostService.BoostView rejectPayment(@PathVariable UUID id, @RequestBody ReasonRequest req) {
        return boostService.rejectPayment(id, req.reason());
    }

    @PostMapping("/boosts/{id}/approve")
    public BoostService.BoostView approveBoost(@PathVariable UUID id, @RequestBody(required = false) ReasonRequest req) {
        return boostService.approve(id, req == null ? null : req.reason());
    }

    @PostMapping("/boosts/{id}/reject")
    public BoostService.BoostView rejectBoost(@PathVariable UUID id, @RequestBody ReasonRequest req) {
        return boostService.reject(id, req.reason());
    }

    @PostMapping("/boosts/{id}/pause")
    public BoostService.BoostView pause(@PathVariable UUID id, @RequestBody(required = false) ReasonRequest req) {
        return boostService.adminPause(id, true, req == null ? null : req.reason());
    }

    @PostMapping("/boosts/{id}/resume")
    public BoostService.BoostView resume(@PathVariable UUID id, @RequestBody(required = false) ReasonRequest req) {
        return boostService.adminPause(id, false, req == null ? null : req.reason());
    }

    @PostMapping("/boosts/{id}/end")
    public BoostService.BoostView end(@PathVariable UUID id, @RequestBody ReasonRequest req) {
        return boostService.end(id, req.reason());
    }

    @PostMapping("/boosts/{id}/refund")
    @PreAuthorize("hasRole('ADMIN')")
    public BoostService.BoostView refund(@PathVariable UUID id, @RequestBody ReasonRequest req) {
        return boostService.refund(id, req.reason());
    }

    @PostMapping("/restrictions")
    @PreAuthorize("hasRole('ADMIN')")
    public PromotionRestriction restrict(@Valid @RequestBody PromoAdminService.RestrictionRequest req) {
        return adminService.restrict(req);
    }

    @GetMapping("/revenue")
    @PreAuthorize("hasRole('ADMIN')")
    public List<PromoAdminService.RevenueRow> revenue(@RequestParam(defaultValue = "30") int days) {
        return adminService.revenue(days);
    }
}
