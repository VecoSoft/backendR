package com.bdreview.platform.promo;

import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.community.CommunityPostResponse;
import com.bdreview.platform.community.CommunityPostService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owner-facing promotion API (V58) — every endpoint requires login, and every business-scoped
 * one re-checks ownership in its service (403 for anyone who isn't the listing's owner).
 */
@RestController
@RequestMapping("/api/v1/promo")
public class PromoController {

    private final BusinessPostService businessPostService;
    private final PromoCreativeService creativeService;
    private final PromoCaptionService captionService;
    private final BoostService boostService;
    private final PromoAnalyticsService analyticsService;
    private final CommunityPostService communityPostService;
    private final BoostPaymentGateway gateway;
    private final PromoAccess access;

    public PromoController(BusinessPostService businessPostService, PromoCreativeService creativeService,
                           PromoCaptionService captionService, BoostService boostService, PromoAnalyticsService analyticsService,
                           CommunityPostService communityPostService, BoostPaymentGateway gateway, PromoAccess access) {
        this.businessPostService = businessPostService;
        this.creativeService = creativeService;
        this.captionService = captionService;
        this.boostService = boostService;
        this.analyticsService = analyticsService;
        this.communityPostService = communityPostService;
        this.gateway = gateway;
        this.access = access;
    }

    // ---- Design Studio ----

    @GetMapping("/businesses/{businessId}/studio")
    public PromoCreativeService.StudioData studio(@PathVariable UUID businessId) {
        return creativeService.studio(CurrentUser.id(), businessId);
    }

    @PostMapping("/businesses/{businessId}/render-model")
    public PromoRenderModel previewModel(@PathVariable UUID businessId, @Valid @RequestBody PromoCreativeService.CreativeRequest req) {
        return creativeService.previewModel(CurrentUser.id(), businessId, req);
    }

    /** V61: a pre-signed slot for the owner's own banner/photo (JPEG). */
    @PostMapping("/businesses/{businessId}/uploads")
    public PromoCreativeService.UploadSlot uploadSlot(@PathVariable UUID businessId) {
        return creativeService.uploadSlot(CurrentUser.id(), businessId);
    }

    @PostMapping("/businesses/{businessId}/creatives")
    public PromoCreativeService.SavedCreative saveCreative(@PathVariable UUID businessId,
                                                           @Valid @RequestBody PromoCreativeService.CreativeRequest req) {
        return creativeService.save(CurrentUser.id(), businessId, req);
    }

    @GetMapping("/businesses/{businessId}/creatives")
    public List<PromoCreativeService.CreativeView> recentCreatives(@PathVariable UUID businessId) {
        return creativeService.recent(CurrentUser.id(), businessId);
    }

    @GetMapping("/creatives/{creativeId}")
    public PromoCreativeService.CreativeView creative(@PathVariable UUID creativeId) {
        return creativeService.get(CurrentUser.id(), creativeId);
    }

    @PutMapping("/creatives/{creativeId}")
    public PromoCreativeService.SavedCreative updateCreative(@PathVariable UUID creativeId,
                                                             @Valid @RequestBody PromoCreativeService.CreativeRequest req) {
        return creativeService.update(CurrentUser.id(), creativeId, req);
    }

    @PostMapping("/creatives/{creativeId}/rendered")
    public PromoCreativeService.CreativeView rendered(@PathVariable UUID creativeId,
                                                      @Valid @RequestBody PromoCreativeService.RenderedRequest req) {
        return creativeService.markRendered(CurrentUser.id(), creativeId, req);
    }

    @PostMapping("/businesses/{businessId}/captions")
    public PromoCaptionService.CaptionResult captions(@PathVariable UUID businessId,
                                                      @Valid @RequestBody PromoCaptionService.CaptionRequest req) {
        return captionService.captions(CurrentUser.id(), businessId, req);
    }

    // ---- Business posts ----

    @PostMapping("/businesses/{businessId}/posts")
    public CommunityPostResponse createPost(@PathVariable UUID businessId,
                                            @Valid @RequestBody BusinessPostService.BusinessPostRequest req) {
        return businessPostService.create(CurrentUser.id(), businessId, req);
    }

    /** The owner's own posts in every state (drafts, pending, rejected, expired), newest first. */
    @GetMapping("/businesses/{businessId}/posts")
    public List<CommunityPostResponse> myPosts(@PathVariable UUID businessId) {
        UUID userId = CurrentUser.id();
        return businessPostService.postsFor(userId, businessId).stream()
                .map(bp -> {
                    try {
                        return communityPostService.getPost(bp.getPostId(), userId);
                    } catch (RuntimeException e) {
                        return null; // deleted
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @PutMapping("/posts/{postId}")
    public CommunityPostResponse updatePost(@PathVariable UUID postId, @Valid @RequestBody BusinessPostService.BusinessPostRequest req) {
        return businessPostService.update(CurrentUser.id(), postId, req);
    }

    @PostMapping("/posts/{postId}/publish")
    public CommunityPostResponse publish(@PathVariable UUID postId) {
        return businessPostService.publish(CurrentUser.id(), postId);
    }

    @DeleteMapping("/posts/{postId}")
    public ResponseEntity<Void> deletePost(@PathVariable UUID postId) {
        businessPostService.delete(CurrentUser.id(), postId);
        return ResponseEntity.noContent().build();
    }

    /** Any logged-in member: "Interested" on an event post (toggles). */
    @PostMapping("/posts/{postId}/interested")
    public Map<String, Integer> interested(@PathVariable UUID postId) {
        return Map.of("interestedCount", businessPostService.toggleInterested(CurrentUser.id(), postId));
    }

    // ---- Boost ----

    public record PackagesResponse(List<BoostPackage> packages, List<BoostPaymentGateway.PaymentInstructions.Option> paymentOptions,
                                   boolean boostsEnabled) {
    }

    @GetMapping("/boost-packages")
    public PackagesResponse packages() {
        var s = access.settings();
        List<BoostPaymentGateway.PaymentInstructions.Option> options = new java.util.ArrayList<>();
        if (s.getBkashNumber() != null && !s.getBkashNumber().isBlank()) {
            options.add(new BoostPaymentGateway.PaymentInstructions.Option("BKASH", s.getBkashNumber()));
        }
        if (s.getNagadNumber() != null && !s.getNagadNumber().isBlank()) {
            options.add(new BoostPaymentGateway.PaymentInstructions.Option("NAGAD", s.getNagadNumber()));
        }
        return new PackagesResponse(boostService.activePackages(), options, s.isBoostsEnabled());
    }

    @PostMapping("/posts/{postId}/boosts")
    public BoostService.BoostView createBoost(@PathVariable UUID postId, @Valid @RequestBody BoostService.CreateBoostRequest req) {
        return boostService.create(CurrentUser.id(), postId, req);
    }

    @PostMapping("/boosts/{boostId}/payment")
    public BoostService.BoostView pay(@PathVariable UUID boostId, @Valid @RequestBody BoostService.PaymentRequest req) {
        return boostService.submitPayment(CurrentUser.id(), boostId, req);
    }

    @PostMapping("/boosts/{boostId}/pause")
    public BoostService.BoostView pause(@PathVariable UUID boostId) {
        return boostService.ownerPause(CurrentUser.id(), boostId, true);
    }

    @PostMapping("/boosts/{boostId}/resume")
    public BoostService.BoostView resume(@PathVariable UUID boostId) {
        return boostService.ownerPause(CurrentUser.id(), boostId, false);
    }

    @GetMapping("/boosts/{boostId}")
    public BoostService.BoostView boost(@PathVariable UUID boostId) {
        return boostService.get(CurrentUser.id(), boostId);
    }

    @GetMapping("/businesses/{businessId}/boosts")
    public List<BoostService.BoostView> boosts(@PathVariable UUID businessId) {
        return boostService.forBusiness(CurrentUser.id(), businessId);
    }

    // ---- Analytics ----

    @GetMapping("/businesses/{businessId}/analytics")
    public PromoAnalyticsService.Analytics analytics(@PathVariable UUID businessId, @RequestParam(defaultValue = "30") int days) {
        return analyticsService.forBusiness(CurrentUser.id(), businessId, days);
    }
}
