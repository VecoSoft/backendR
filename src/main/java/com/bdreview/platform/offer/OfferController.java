package com.bdreview.platform.offer;

import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * "Offers / Discounts". GET routes are public (see auth.SecurityConfig's permitAll for
 * GET /api/v1/offers/**), same posture as the Community feed; everything else (create/update/
 * submit/cancel/claim/redeem/save/admin actions) requires a logged-in user via CurrentUser.
 * Admin actions are gated inline (CurrentUser.requireRole("ADMIN")) rather than a separate
 * controller, mirroring claim.BusinessClaimController's exact shape.
 */
@RestController
@RequestMapping("/api/v1/offers")
public class OfferController {

    private final OfferService offerService;

    public OfferController(OfferService offerService) {
        this.offerService = offerService;
    }

    // -----------------------------------------------------------------
    // Business owner
    // -----------------------------------------------------------------

    @PostMapping
    public ResponseEntity<OfferResponse> create(@Valid @RequestBody CreateOfferRequest request) {
        return ResponseEntity.ok(offerService.createOffer(CurrentUser.id(), request));
    }

    @PatchMapping("/{offerId}")
    public ResponseEntity<OfferResponse> update(@PathVariable UUID offerId, @Valid @RequestBody UpdateOfferRequest request) {
        return ResponseEntity.ok(offerService.updateOffer(CurrentUser.id(), offerId, request));
    }

    @PostMapping("/{offerId}/submit")
    public ResponseEntity<OfferResponse> submit(@PathVariable UUID offerId) {
        return ResponseEntity.ok(offerService.submitOffer(CurrentUser.id(), offerId));
    }

    @DeleteMapping("/{offerId}")
    public ResponseEntity<Void> cancel(@PathVariable UUID offerId) {
        offerService.cancelOffer(CurrentUser.id(), offerId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/business/{businessId}/mine")
    public ResponseEntity<PageResponse<OfferResponse>> businessOffersForOwner(
            @PathVariable UUID businessId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(offerService.businessOffersForOwner(CurrentUser.id(), businessId, page, size));
    }

    @GetMapping("/{offerId}/analytics")
    public ResponseEntity<OfferAnalyticsResponse> analytics(@PathVariable UUID offerId) {
        return ResponseEntity.ok(offerService.analytics(CurrentUser.id(), offerId));
    }

    // -----------------------------------------------------------------
    // Public browse
    // -----------------------------------------------------------------

    @GetMapping
    public ResponseEntity<PageResponse<OfferResponse>> feed(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(required = false) OfferAvailability availability,
            @RequestParam(defaultValue = "false") boolean endingSoon,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(offerService.feed(categoryId, areaId, availability, endingSoon, page, size, CurrentUser.idOrNull()));
    }

    @GetMapping("/{offerId}")
    public ResponseEntity<OfferResponse> get(@PathVariable UUID offerId) {
        return ResponseEntity.ok(offerService.getOffer(offerId, CurrentUser.idOrNull()));
    }

    /** Business-profile banner — a business's own currently-active offers. */
    @GetMapping("/business/{businessId}")
    public ResponseEntity<List<OfferResponse>> businessOffers(@PathVariable UUID businessId) {
        return ResponseEntity.ok(offerService.businessOffers(businessId, CurrentUser.idOrNull()));
    }

    // -----------------------------------------------------------------
    // Claim / Redeem / Save
    // -----------------------------------------------------------------

    @PostMapping("/{offerId}/claim")
    public ResponseEntity<OfferClaimResponse> claim(@PathVariable UUID offerId) {
        return ResponseEntity.ok(offerService.claimOffer(CurrentUser.id(), offerId));
    }

    @PostMapping("/redeem")
    public ResponseEntity<OfferClaimResponse> redeem(@Valid @RequestBody RedeemOfferRequest request) {
        return ResponseEntity.ok(offerService.redeemOffer(CurrentUser.id(), request));
    }

    @GetMapping("/claims/mine")
    public ResponseEntity<PageResponse<OfferClaimResponse>> myClaims(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(offerService.myClaims(CurrentUser.id(), page, size));
    }

    @PostMapping("/{offerId}/save")
    public ResponseEntity<Void> save(@PathVariable UUID offerId) {
        offerService.saveOffer(CurrentUser.id(), offerId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{offerId}/save")
    public ResponseEntity<Void> unsave(@PathVariable UUID offerId) {
        offerService.unsaveOffer(CurrentUser.id(), offerId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/saved/mine")
    public ResponseEntity<PageResponse<OfferResponse>> mySavedOffers(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(offerService.mySavedOffers(CurrentUser.id(), page, size));
    }

    // -----------------------------------------------------------------
    // Admin moderation
    // -----------------------------------------------------------------

    @GetMapping("/admin/queue")
    public ResponseEntity<PageResponse<OfferResponse>> adminQueue(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(offerService.adminQueue(page, size));
    }

    @PostMapping("/admin/{offerId}/approve")
    public ResponseEntity<OfferResponse> adminApprove(@PathVariable UUID offerId) {
        return ResponseEntity.ok(offerService.adminApprove(offerId));
    }

    @PostMapping("/admin/{offerId}/reject")
    public ResponseEntity<OfferResponse> adminReject(@PathVariable UUID offerId, @RequestBody RejectOfferRequest request) {
        return ResponseEntity.ok(offerService.adminReject(offerId, request));
    }

    @PostMapping("/admin/{offerId}/remove")
    public ResponseEntity<Void> adminRemove(@PathVariable UUID offerId) {
        offerService.adminRemove(offerId);
        return ResponseEntity.noContent().build();
    }
}
