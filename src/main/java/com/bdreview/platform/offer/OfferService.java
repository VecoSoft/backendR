package com.bdreview.platform.offer;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.catalog.MenuItem;
import com.bdreview.platform.catalog.MenuItemRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.PageRequestDefaults;
import com.bdreview.platform.common.PageResponse;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.moderation.AuditLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Offers / Discounts" — verified businesses publish time-boxed offers;
 * users browse/claim (in-store, via a unique redemption code the business
 * later confirms) or get deep-linked to the business's existing ordering
 * flow (online — see the plan's "Order Now" decision, no new Commerce
 * surface here). Admin approval mirrors claim.BusinessClaimService exactly.
 */
@Service
public class OfferService {

    private static final int REDEMPTION_CODE_LENGTH = 8;
    private static final String REDEMPTION_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I — avoids ambiguous chars when read aloud/typed in-store
    private static final int MAX_CODE_GENERATION_ATTEMPTS = 5;

    private final OfferRepository offerRepository;
    private final OfferClaimRepository claimRepository;
    private final OfferSaveRepository saveRepository;
    private final BusinessRepository businessRepository;
    private final MenuItemRepository menuItemRepository;
    private final AuditLogService auditLogService;
    private final OfferNotifier offerNotifier;
    private final SecureRandom random = new SecureRandom();

    public OfferService(OfferRepository offerRepository,
                         OfferClaimRepository claimRepository,
                         OfferSaveRepository saveRepository,
                         BusinessRepository businessRepository,
                         MenuItemRepository menuItemRepository,
                         AuditLogService auditLogService,
                         OfferNotifier offerNotifier) {
        this.offerRepository = offerRepository;
        this.claimRepository = claimRepository;
        this.saveRepository = saveRepository;
        this.businessRepository = businessRepository;
        this.menuItemRepository = menuItemRepository;
        this.auditLogService = auditLogService;
        this.offerNotifier = offerNotifier;
    }

    // -----------------------------------------------------------------
    // Business owner: create / update / submit / cancel
    // -----------------------------------------------------------------

    @Transactional
    public OfferResponse createOffer(UUID userId, CreateOfferRequest request) {
        Business business = requireOwnedVerifiedBusiness(userId, request.businessId());
        validateOfferFields(request.offerType(), request.discountValue(), request.validFrom(), request.validUntil());
        validateMenuItem(business.getId(), request.menuItemId());

        Offer offer = offerRepository.save(Offer.builder()
                .businessId(business.getId())
                .title(request.title().trim())
                .offerType(request.offerType())
                .discountValue(request.discountValue())
                .originalPrice(request.originalPrice())
                .offerPrice(request.offerPrice())
                .description(blankToNull(request.description()))
                .termsAndConditions(blankToNull(request.termsAndConditions()))
                .imageUrl(blankToNull(request.imageUrl()))
                .menuItemId(request.menuItemId())
                .validFrom(request.validFrom())
                .validUntil(request.validUntil())
                .availability(request.availability())
                .status(OfferStatus.DRAFT)
                .maxTotalRedemptions(request.maxTotalRedemptions())
                .maxRedemptionsPerUser(request.maxRedemptionsPerUser())
                .build());

        return toResponse(offer, business, userId);
    }

    @Transactional
    public OfferResponse updateOffer(UUID userId, UUID offerId, UpdateOfferRequest request) {
        Offer offer = findOfferOrThrow(offerId);
        Business business = requireOwnedBusiness(userId, offer.getBusinessId());
        if (offer.getStatus() == OfferStatus.CANCELLED) {
            throw new BadRequestException("A cancelled offer can't be edited");
        }
        validateOfferFields(request.offerType(), request.discountValue(), request.validFrom(), request.validUntil());
        validateMenuItem(offer.getBusinessId(), request.menuItemId());

        offer.setTitle(request.title().trim());
        offer.setOfferType(request.offerType());
        offer.setDiscountValue(request.discountValue());
        offer.setOriginalPrice(request.originalPrice());
        offer.setOfferPrice(request.offerPrice());
        offer.setDescription(blankToNull(request.description()));
        offer.setTermsAndConditions(blankToNull(request.termsAndConditions()));
        offer.setImageUrl(blankToNull(request.imageUrl()));
        offer.setMenuItemId(request.menuItemId());
        offer.setValidFrom(request.validFrom());
        offer.setValidUntil(request.validUntil());
        offer.setAvailability(request.availability());
        offer.setMaxTotalRedemptions(request.maxTotalRedemptions());
        offer.setMaxRedemptionsPerUser(request.maxRedemptionsPerUser());
        offerRepository.save(offer);

        return toResponse(offer, business, userId);
    }

    /** Offers go live immediately on submit — no admin approval gate (see OfferController). */
    @Transactional
    public OfferResponse submitOffer(UUID userId, UUID offerId) {
        Offer offer = findOfferOrThrow(offerId);
        Business business = requireOwnedBusiness(userId, offer.getBusinessId());
        if (offer.getStatus() != OfferStatus.DRAFT && offer.getStatus() != OfferStatus.REJECTED) {
            throw new BadRequestException("Only a draft or rejected offer can be submitted for approval");
        }
        offer.setStatus(OfferStatus.ACTIVE);
        offer.setRejectionReason(null);
        offerRepository.save(offer);
        return toResponse(offer, business, userId);
    }

    @Transactional
    public void cancelOffer(UUID userId, UUID offerId) {
        Offer offer = findOfferOrThrow(offerId);
        requireOwnedBusiness(userId, offer.getBusinessId());
        if (offer.getStatus() == OfferStatus.CANCELLED) {
            return;
        }
        offer.setStatus(OfferStatus.CANCELLED);
        offerRepository.save(offer);
    }

    private void validateOfferFields(OfferType type, BigDecimal discountValue, Instant validFrom, Instant validUntil) {
        if (!validFrom.isBefore(validUntil)) {
            throw new BadRequestException("Valid Until must be after Valid From");
        }
        boolean numericType = type == OfferType.PERCENTAGE_DISCOUNT || type == OfferType.FIXED_AMOUNT_DISCOUNT;
        if (numericType && (discountValue == null || discountValue.signum() <= 0)) {
            throw new BadRequestException("A discount value is required for this offer type");
        }
        if (type == OfferType.PERCENTAGE_DISCOUNT && discountValue.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BadRequestException("A percentage discount can't exceed 100%");
        }
    }

    private void validateMenuItem(UUID businessId, UUID menuItemId) {
        if (menuItemId == null) {
            return;
        }
        MenuItem item = menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> new BadRequestException("Menu item not found"));
        if (!item.getBusinessId().equals(businessId)) {
            throw new BadRequestException("Menu item must belong to the same business");
        }
    }

    // -----------------------------------------------------------------
    // Public browse
    // -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<OfferResponse> feed(UUID categoryId, UUID areaId, OfferAvailability availability,
                                              boolean endingSoon, int page, int size, UUID viewerUserId) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size));
        Set<OfferAvailability> availabilityValues = availability == null
                ? EnumSet.allOf(OfferAvailability.class)
                : EnumSet.of(availability, OfferAvailability.BOTH);

        Page<Offer> offers = endingSoon
                ? offerRepository.findActiveFeedEndingBefore(OfferStatus.ACTIVE, Instant.now(), categoryId, areaId,
                        availabilityValues, Instant.now().plus(24, ChronoUnit.HOURS), pageable)
                : offerRepository.findActiveFeed(OfferStatus.ACTIVE, Instant.now(), categoryId, areaId, availabilityValues, pageable);
        return buildPageResponse(offers, viewerUserId);
    }

    @Transactional
    public OfferResponse getOffer(UUID offerId, UUID viewerUserId) {
        Offer offer = findOfferOrThrow(offerId);
        Business business = businessRepository.findById(offer.getBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));

        boolean isOwnerOrAdmin = business.getOwnerUserId().equals(viewerUserId) || CurrentUser.hasRole("ADMIN");
        if (offer.getEffectiveStatus() != OfferStatus.ACTIVE && !isOwnerOrAdmin) {
            throw new ResourceNotFoundException("Offer not found");
        }

        offerRepository.adjustViewCount(offerId, 1);
        offer.setViewCount(offer.getViewCount() + 1); // reflect the bump in this response without a re-query (avoids a same-transaction stale-read of the just-issued atomic UPDATE)
        return toResponse(offer, business, viewerUserId);
    }

    /** Business-profile banner — a business's own currently-active offers, small result set, no paging. */
    @Transactional(readOnly = true)
    public List<OfferResponse> businessOffers(UUID businessId, UUID viewerUserId) {
        Business business = businessRepository.findById(businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        List<Offer> offers = offerRepository.findByBusinessIdAndStatusAndValidUntilAfterOrderByValidUntilAsc(
                businessId, OfferStatus.ACTIVE, Instant.now());
        Set<UUID> savedOfferIds = savedOfferIds(viewerUserId, offers.stream().map(Offer::getId).toList());
        return offers.stream().map(o -> toResponse(o, business, savedOfferIds.contains(o.getId()))).toList();
    }

    /**
     * Batched "one badge-worthy offer per business" lookup for a search/list results page —
     * see BusinessService#search. One query total, not one per row; businesses with no active
     * offer are simply absent from the returned map rather than mapped to null.
     */
    @Transactional(readOnly = true)
    public Map<UUID, ActiveOfferSummary> activeOfferSummariesByBusiness(List<UUID> businessIds) {
        if (businessIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ActiveOfferSummary> byBusiness = new HashMap<>();
        for (Offer offer : offerRepository.findByBusinessIdInAndStatusAndValidUntilAfterOrderByValidUntilAsc(
                businessIds, OfferStatus.ACTIVE, Instant.now())) {
            byBusiness.putIfAbsent(offer.getBusinessId(), ActiveOfferSummary.from(offer));
        }
        return byBusiness;
    }

    /** Owner dashboard — every status, not just active. */
    @Transactional(readOnly = true)
    public PageResponse<OfferResponse> businessOffersForOwner(UUID userId, UUID businessId, int page, int size) {
        Business business = requireOwnedBusiness(userId, businessId);
        Pageable pageable = PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size));
        Page<Offer> offers = offerRepository.findByBusinessIdOrderByCreatedAtDesc(businessId, pageable);
        Page<OfferResponse> mapped = offers.map(o -> toResponse(o, business, false));
        return PageResponse.of(mapped);
    }

    // -----------------------------------------------------------------
    // Claim / Redeem
    // -----------------------------------------------------------------

    @Transactional
    public OfferClaimResponse claimOffer(UUID userId, UUID offerId) {
        Offer offer = findOfferOrThrow(offerId);
        Business business = businessRepository.findById(offer.getBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));

        if (business.getOwnerUserId().equals(userId)) {
            throw new BadRequestException("You can't claim your own business's offer");
        }
        if (offer.isPastValidUntil()) {
            throw new BadRequestException("This offer has expired");
        }
        if (offer.getStatus() != OfferStatus.ACTIVE) {
            throw new BadRequestException("This offer is not available");
        }
        if (offer.getMaxRedemptionsPerUser() != null) {
            long already = claimRepository.countByOfferIdAndUserIdAndStatusNot(offerId, userId, OfferClaimStatus.CANCELLED);
            if (already >= offer.getMaxRedemptionsPerUser()) {
                throw new BadRequestException("You have already claimed this offer");
            }
        }
        if (offer.getMaxTotalRedemptions() != null) {
            long totalClaims = claimRepository.countByOfferIdAndStatusNot(offerId, OfferClaimStatus.CANCELLED);
            if (totalClaims >= offer.getMaxTotalRedemptions()) {
                throw new BadRequestException("This offer is fully claimed");
            }
        }

        String code = generateUniqueRedemptionCode();
        OfferClaim claim = claimRepository.save(OfferClaim.builder()
                .offerId(offerId).userId(userId).redemptionCode(code).status(OfferClaimStatus.CLAIMED).build());
        offerRepository.adjustClaimCount(offerId, 1);

        if (!business.getOwnerUserId().equals(userId)) {
            offerNotifier.offerClaimed(business.getOwnerUserId(), offerId, offer.getTitle());
        }
        return toClaimResponse(claim, offer.getTitle(), business.getName());
    }

    @Transactional
    public OfferClaimResponse redeemOffer(UUID staffUserId, RedeemOfferRequest request) {
        OfferClaim claim = claimRepository.findByRedemptionCode(request.redemptionCode().trim().toUpperCase(java.util.Locale.ROOT))
                .orElseThrow(() -> new BadRequestException("Invalid redemption code"));
        Offer offer = findOfferOrThrow(claim.getOfferId());
        Business business = requireOwnedBusiness(staffUserId, offer.getBusinessId());

        if (offer.getStatus() == OfferStatus.CANCELLED) {
            throw new BadRequestException("This offer has been cancelled");
        }
        if (claim.getStatus() != OfferClaimStatus.CLAIMED) {
            throw new BadRequestException("This code has already been used or is no longer valid");
        }

        claim.setStatus(OfferClaimStatus.REDEEMED);
        claim.setRedeemedAt(Instant.now());
        claim.setRedeemedByUserId(staffUserId);
        claimRepository.save(claim);
        offerRepository.adjustRedemptionCount(offer.getId(), 1);

        offerNotifier.offerRedeemed(claim.getUserId(), offer.getId(), offer.getTitle());
        return toClaimResponse(claim, offer.getTitle(), business.getName());
    }

    @Transactional(readOnly = true)
    public PageResponse<OfferClaimResponse> myClaims(UUID userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size));
        Page<OfferClaim> claims = claimRepository.findByUserIdOrderByClaimedAtDesc(userId, pageable);
        List<UUID> offerIds = claims.getContent().stream().map(OfferClaim::getOfferId).distinct().toList();
        Map<UUID, Offer> offersById = offerRepository.findAllById(offerIds).stream()
                .collect(Collectors.toMap(Offer::getId, o -> o));
        Map<UUID, Business> businessesById = loadBusinesses(offersById.values().stream().map(Offer::getBusinessId).distinct().toList());

        Page<OfferClaimResponse> mapped = claims.map(c -> {
            Offer o = offersById.get(c.getOfferId());
            Business b = o == null ? null : businessesById.get(o.getBusinessId());
            return toClaimResponse(c, o == null ? null : o.getTitle(), b == null ? null : b.getName());
        });
        return PageResponse.of(mapped);
    }

    // -----------------------------------------------------------------
    // Save / unsave
    // -----------------------------------------------------------------

    @Transactional
    public void saveOffer(UUID userId, UUID offerId) {
        findOfferOrThrow(offerId);
        if (saveRepository.existsByUserIdAndOfferId(userId, offerId)) {
            return;
        }
        saveRepository.save(OfferSave.builder().userId(userId).offerId(offerId).build());
    }

    @Transactional
    public void unsaveOffer(UUID userId, UUID offerId) {
        saveRepository.deleteByUserIdAndOfferId(userId, offerId);
    }

    @Transactional(readOnly = true)
    public PageResponse<OfferResponse> mySavedOffers(UUID userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size));
        Page<OfferSave> saves = saveRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        List<UUID> offerIds = saves.getContent().stream().map(OfferSave::getOfferId).toList();
        Map<UUID, Offer> offersById = offerRepository.findAllById(offerIds).stream()
                .collect(Collectors.toMap(Offer::getId, o -> o));
        Map<UUID, Business> businessesById = loadBusinesses(offersById.values().stream().map(Offer::getBusinessId).distinct().toList());

        Page<OfferResponse> mapped = saves.map(s -> {
            Offer o = offersById.get(s.getOfferId());
            return o == null ? null : toResponse(o, businessesById.get(o.getBusinessId()), true);
        });
        return PageResponse.of(mapped);
    }

    // -----------------------------------------------------------------
    // Analytics
    // -----------------------------------------------------------------

    public OfferAnalyticsResponse analytics(UUID userId, UUID offerId) {
        Offer offer = findOfferOrThrow(offerId);
        requireOwnedBusiness(userId, offer.getBusinessId());
        return new OfferAnalyticsResponse(offer.getViewCount(), offer.getClaimCount(), offer.getRedemptionCount());
    }

    // -----------------------------------------------------------------
    // Admin moderation — mirrors claim.BusinessClaimService's resolve() pattern
    // -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<OfferResponse> adminQueue(int page, int size) {
        CurrentUser.requireRole("ADMIN");
        Pageable pageable = PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size));
        Page<Offer> offers = offerRepository.findByStatusOrderByCreatedAtAsc(OfferStatus.PENDING_APPROVAL, pageable);
        return buildPageResponse(offers, null);
    }

    @Transactional
    public OfferResponse adminApprove(UUID offerId) {
        CurrentUser.requireRole("ADMIN");
        Offer offer = findOfferOrThrow(offerId);
        if (offer.getStatus() != OfferStatus.PENDING_APPROVAL) {
            throw new BadRequestException("Only a pending offer can be approved");
        }
        Business business = businessRepository.findById(offer.getBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));

        offer.setStatus(OfferStatus.ACTIVE);
        offer.setRejectionReason(null);
        offerRepository.save(offer);
        auditLogService.log("OFFER", offerId, "APPROVED", CurrentUser.id(), null);
        offerNotifier.offerApproved(business.getOwnerUserId(), offerId, offer.getTitle());
        return toResponse(offer, business, null);
    }

    @Transactional
    public OfferResponse adminReject(UUID offerId, RejectOfferRequest request) {
        CurrentUser.requireRole("ADMIN");
        Offer offer = findOfferOrThrow(offerId);
        if (offer.getStatus() != OfferStatus.PENDING_APPROVAL) {
            throw new BadRequestException("Only a pending offer can be rejected");
        }
        Business business = businessRepository.findById(offer.getBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));

        offer.setStatus(OfferStatus.REJECTED);
        offer.setRejectionReason(blankToNull(request.reason()));
        offerRepository.save(offer);
        auditLogService.log("OFFER", offerId, "REJECTED", CurrentUser.id(), request.reason());
        offerNotifier.offerRejected(business.getOwnerUserId(), offerId, offer.getTitle(), request.reason());
        return toResponse(offer, business, null);
    }

    @Transactional
    public void adminRemove(UUID offerId) {
        CurrentUser.requireRole("ADMIN");
        Offer offer = findOfferOrThrow(offerId);
        offer.setStatus(OfferStatus.CANCELLED);
        offerRepository.save(offer);
        auditLogService.log("OFFER", offerId, "REMOVED", CurrentUser.id(), null);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private Offer findOfferOrThrow(UUID offerId) {
        return offerRepository.findById(offerId).orElseThrow(() -> new ResourceNotFoundException("Offer not found"));
    }

    private Business requireOwnedBusiness(UUID userId, UUID businessId) {
        Business business = businessRepository.findById(businessId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        if (!business.getOwnerUserId().equals(userId)) {
            throw new ForbiddenException("You don't own this business");
        }
        return business;
    }

    private Business requireOwnedVerifiedBusiness(UUID userId, UUID businessId) {
        Business business = requireOwnedBusiness(userId, businessId);
        if (!business.isVerified()) {
            throw new ForbiddenException("Only verified businesses can create offers");
        }
        return business;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** SecureRandom + retry-on-collision — same shape as qr.QrService#generateUniqueToken, kept as its own small copy rather than a shared utility (see the plan). */
    private String generateUniqueRedemptionCode() {
        for (int attempt = 0; attempt < MAX_CODE_GENERATION_ATTEMPTS; attempt++) {
            StringBuilder sb = new StringBuilder(REDEMPTION_CODE_LENGTH);
            for (int i = 0; i < REDEMPTION_CODE_LENGTH; i++) {
                sb.append(REDEMPTION_CODE_ALPHABET.charAt(random.nextInt(REDEMPTION_CODE_ALPHABET.length())));
            }
            String candidate = sb.toString();
            if (!claimRepository.existsByRedemptionCode(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique redemption code");
    }

    private Set<UUID> savedOfferIds(UUID viewerUserId, List<UUID> offerIds) {
        if (viewerUserId == null || offerIds.isEmpty()) {
            return Set.of();
        }
        return offerIds.stream().filter(id -> saveRepository.existsByUserIdAndOfferId(viewerUserId, id)).collect(Collectors.toSet());
    }

    private Map<UUID, Business> loadBusinesses(List<UUID> businessIds) {
        if (businessIds.isEmpty()) {
            return Map.of();
        }
        return businessRepository.findAllById(businessIds).stream().collect(Collectors.toMap(Business::getId, b -> b));
    }

    private PageResponse<OfferResponse> buildPageResponse(Page<Offer> offers, UUID viewerUserId) {
        List<Offer> content = offers.getContent();
        Map<UUID, Business> businessesById = loadBusinesses(content.stream().map(Offer::getBusinessId).distinct().toList());
        Set<UUID> savedOfferIds = savedOfferIds(viewerUserId, content.stream().map(Offer::getId).toList());

        Page<OfferResponse> mapped = offers.map(o -> toResponse(o, businessesById.get(o.getBusinessId()), savedOfferIds.contains(o.getId())));
        return PageResponse.of(mapped);
    }

    private OfferResponse toResponse(Offer offer, Business business, UUID viewerUserId) {
        boolean saved = viewerUserId != null && saveRepository.existsByUserIdAndOfferId(viewerUserId, offer.getId());
        return toResponse(offer, business, saved);
    }

    private OfferResponse toResponse(Offer offer, Business business, boolean saved) {
        String menuItemName = offer.getMenuItemId() == null ? null
                : menuItemRepository.findById(offer.getMenuItemId()).map(MenuItem::getName).orElse(null);
        return new OfferResponse(
                offer.getId(),
                offer.getBusinessId(),
                business == null ? null : business.getName(),
                business == null ? null : business.getSlug(),
                business == null ? null : business.getLogoUrl(),
                business != null && business.isVerified(),
                business == null ? null : business.getAverageRating(),
                business == null ? 0 : business.getReviewCount(),
                business == null ? null : business.getArea().getName(),
                business == null ? null : business.getArea().getCity().getName(),
                offer.getTitle(),
                offer.getOfferType(),
                offer.getDiscountValue(),
                offer.getOriginalPrice(),
                offer.getOfferPrice(),
                offer.getDescription(),
                offer.getTermsAndConditions(),
                offer.getImageUrl(),
                offer.getMenuItemId(),
                menuItemName,
                offer.getValidFrom(),
                offer.getValidUntil(),
                offer.getAvailability(),
                offer.getStatus(),
                offer.getEffectiveStatus(),
                offer.getMaxTotalRedemptions(),
                offer.getMaxRedemptionsPerUser(),
                offer.getViewCount(),
                offer.getClaimCount(),
                offer.getRedemptionCount(),
                offer.getRejectionReason(),
                saved,
                offer.getCreatedAt(),
                offer.getUpdatedAt());
    }

    private OfferClaimResponse toClaimResponse(OfferClaim claim, String offerTitle, String businessName) {
        return new OfferClaimResponse(
                claim.getId(), claim.getOfferId(), offerTitle, businessName, claim.getRedemptionCode(),
                claim.getStatus(), claim.getClaimedAt(), claim.getRedeemedAt());
    }
}
