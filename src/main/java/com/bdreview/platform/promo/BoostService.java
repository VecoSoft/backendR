package com.bdreview.platform.promo;

import com.bdreview.platform.business.AreaRepository;
import com.bdreview.platform.business.Business;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ConflictException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.community.CommunityPostRepository;
import com.bdreview.platform.community.moderation.CommunityModerationService;
import com.bdreview.platform.moderation.AuditLogService;
import com.bdreview.platform.offer.Offer;
import com.bdreview.platform.offer.OfferRepository;
import com.bdreview.platform.promo.PromoEnums.BoostStatus;
import com.bdreview.platform.promo.PromoEnums.BusinessPostStatus;
import com.bdreview.platform.promo.PromoEnums.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * Paid Boost (V58). Owner: pick a package → targeting (areas OR a 2/5/10 km radius around the
 * business) → start date → pay (manual bKash/Nagad + trx id, see {@link BoostPaymentGateway}).
 * A boost can never become ACTIVE without a verified payment. Admin: verify/reject payment
 * (ADMIN), approve/reject (MODERATOR or ADMIN), pause/end (MODERATOR or ADMIN), refund and
 * re-target (ADMIN). Every transition is audit-logged with before/after.
 */
@Service
public class BoostService {

    static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");
    static final Set<Integer> RADIUS_OPTIONS_KM = Set.of(2, 5, 10);
    static final int MAX_TARGET_AREAS = 10;
    static final int MAX_DAYS_AHEAD = 30;
    /** An offer must still run this long after a boost starts — nobody should pay for a few hours of reach. */
    static final Duration MIN_OFFER_RUNWAY = Duration.ofHours(12);

    static boolean offerHasRunway(Offer offer, Instant startAt) {
        return offer != null && offer.isCurrentlyActive() && offer.getValidUntil().isAfter(startAt.plus(MIN_OFFER_RUNWAY));
    }
    static final EnumSet<BoostStatus> OPEN = EnumSet.of(BoostStatus.PENDING_PAYMENT, BoostStatus.PENDING_REVIEW,
            BoostStatus.ACTIVE, BoostStatus.PAUSED);

    public record CreateBoostRequest(@NotNull UUID packageId, @Size(max = MAX_TARGET_AREAS) List<UUID> targetAreaIds,
                                     Integer radiusKm, LocalDate startDate) {
    }

    public record PaymentRequest(@NotNull PaymentMethod method, @NotNull @Size(max = 60) String transactionId) {
    }

    public record TargetingRequest(@Size(max = MAX_TARGET_AREAS) List<UUID> targetAreaIds, Integer radiusKm) {
    }

    public record BoostView(UUID id, UUID postId, UUID businessId, String businessName, String packageName, BoostStatus status,
                            List<UUID> targetAreaIds, List<String> targetAreaNames, Integer radiusKm,
                            Instant startAt, Instant endAt, BigDecimal priceBdt, PaymentMethod paymentMethod,
                            String paymentRef, Instant paymentSubmittedAt, Instant paymentVerifiedAt, BigDecimal paidAmount,
                            int estImpressions, int impressionsServed, String rejectionReason, String refundReason,
                            Instant createdAt, BoostPaymentGateway.PaymentInstructions payment) {
    }

    private final BoostRepository boostRepository;
    private final BoostPackageRepository packageRepository;
    private final BusinessPostRepository businessPostRepository;
    private final CommunityPostRepository communityPostRepository;
    private final OfferRepository offerRepository;
    private final AreaRepository areaRepository;
    private final PromoAccess access;
    private final BoostPaymentGateway gateway;
    private final AuditLogService auditLogService;
    private final com.bdreview.platform.business.BusinessRepository businessRepository;

    public BoostService(BoostRepository boostRepository, BoostPackageRepository packageRepository,
                        BusinessPostRepository businessPostRepository, CommunityPostRepository communityPostRepository,
                        OfferRepository offerRepository, AreaRepository areaRepository, PromoAccess access,
                        BoostPaymentGateway gateway, AuditLogService auditLogService,
                        com.bdreview.platform.business.BusinessRepository businessRepository) {
        this.businessRepository = businessRepository;
        this.boostRepository = boostRepository;
        this.packageRepository = packageRepository;
        this.businessPostRepository = businessPostRepository;
        this.communityPostRepository = communityPostRepository;
        this.offerRepository = offerRepository;
        this.areaRepository = areaRepository;
        this.access = access;
        this.gateway = gateway;
        this.auditLogService = auditLogService;
    }

    // -----------------------------------------------------------------
    // Owner
    // -----------------------------------------------------------------

    public List<BoostPackage> activePackages() {
        return packageRepository.findByActiveTrueOrderBySortOrderAscPriceBdtAsc();
    }

    @Transactional
    public BoostView create(UUID userId, UUID postId, CreateBoostRequest req) {
        if (!access.settings().isBoostsEnabled()) {
            throw new ForbiddenException("Boosts are turned off right now.");
        }
        BusinessPost bp = businessPostRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Business post not found"));
        Business business = access.requireCanPromote(userId, bp.getBusinessId());
        BusinessPostStatus status = BusinessPostService.effectiveStatus(bp, communityPostRepository.findById(postId).orElse(null));
        if (status != BusinessPostStatus.PUBLISHED) {
            throw new BadRequestException("Only a published post can be boosted.");
        }
        if (boostRepository.existsByPostIdAndStatusIn(postId, OPEN)) {
            throw new ConflictException("This post already has a boost in progress.");
        }
        BoostPackage pkg = packageRepository.findById(req.packageId()).filter(BoostPackage::isActive)
                .orElseThrow(() -> new BadRequestException("That package isn't available."));

        Instant now = Instant.now();
        LocalDate today = LocalDate.now(ZONE);
        LocalDate start = req.startDate() == null ? today : req.startDate();
        if (start.isBefore(today) || start.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw new BadRequestException("Start date must be between today and " + MAX_DAYS_AHEAD + " days from now.");
        }
        Instant startAt = start.equals(today) ? now : start.atStartOfDay(ZONE).toInstant();
        Instant endAt = startAt.plus(Duration.ofDays(pkg.getDurationDays()));
        if (bp.getOfferId() != null) {
            if (!offerHasRunway(offerRepository.findById(bp.getOfferId()).orElse(null), startAt)) {
                throw new BadRequestException("This offer ends less than 12 hours after the boost would start. "
                        + "Extend the offer, pick an earlier start date, or boost another post.");
            }
        }

        Boost boost = Boost.builder()
                .businessId(business.getId())
                .postId(postId)
                .packageId(pkg.getId())
                .packageName(pkg.getName())
                .estImpressions(pkg.getEstImpressions())
                .priceBdt(pkg.getPriceBdt())
                .startAt(startAt)
                .endAt(endAt)
                .status(BoostStatus.PENDING_PAYMENT)
                .createdBy(userId)
                .build();
        applyTargeting(boost, business, req.targetAreaIds(), req.radiusKm(), pkg.getMaxRadiusKm());
        return view(boostRepository.save(boost), true);
    }

    @Transactional
    public BoostView submitPayment(UUID userId, UUID boostId, PaymentRequest req) {
        Boost boost = requireOwnedBoost(userId, boostId);
        if (boost.getStatus() != BoostStatus.PENDING_PAYMENT) {
            throw new BadRequestException("This boost isn't waiting for payment.");
        }
        gateway.submit(boost, req.method(), req.transactionId());
        try {
            return view(boostRepository.saveAndFlush(boost), true);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("That transaction ID has already been used for another boost.");
        }
    }

    @Transactional
    public BoostView ownerPause(UUID userId, UUID boostId, boolean pause) {
        Boost boost = requireOwnedBoost(userId, boostId);
        if (pause && boost.getStatus() != BoostStatus.ACTIVE) {
            throw new BadRequestException("Only a running boost can be paused.");
        }
        if (!pause && boost.getStatus() != BoostStatus.PAUSED) {
            throw new BadRequestException("Only a paused boost can be resumed.");
        }
        boost.setStatus(pause ? BoostStatus.PAUSED : BoostStatus.ACTIVE);
        return view(boostRepository.save(boost), true);
    }

    @Transactional(readOnly = true)
    public List<BoostView> forBusiness(UUID userId, UUID businessId) {
        access.requireOwned(userId, businessId);
        return boostRepository.findByBusinessIdOrderByCreatedAtDesc(businessId).stream().map(b -> view(b, true)).toList();
    }

    @Transactional(readOnly = true)
    public BoostView get(UUID userId, UUID boostId) {
        return view(requireOwnedBoost(userId, boostId), true);
    }

    // -----------------------------------------------------------------
    // Admin — verify payment is ADMIN only; review/pause/end are MODERATOR or ADMIN
    // -----------------------------------------------------------------

    @Transactional
    public BoostView verifyPayment(UUID boostId, BigDecimal paidAmount, String note) {
        CurrentUser.requireRole("ADMIN");
        Boost b = requireBoost(boostId);
        if (b.getStatus() != BoostStatus.PENDING_PAYMENT || b.getPaymentRef() == null) {
            throw new BadRequestException("There's no submitted payment to verify on this boost.");
        }
        Map<String, Object> before = snapshot(b);
        b.setPaymentVerifiedAt(Instant.now());
        b.setPaidAmount(paidAmount == null ? b.getPriceBdt() : paidAmount);
        if (b.getPaidAmount().compareTo(b.getPriceBdt()) < 0) {
            throw new BadRequestException("The verified amount is less than the package price (৳" + b.getPriceBdt() + ").");
        }
        if (access.settings().isBoostRequiresReview()) {
            b.setStatus(BoostStatus.PENDING_REVIEW);
        } else {
            activate(b);
        }
        boostRepository.save(b);
        audit(b, "BOOST_PAYMENT_VERIFIED", blank(note, "Payment verified: " + b.getPaymentMethod() + " " + b.getPaymentRef()), before);
        return view(b, false);
    }

    @Transactional
    public BoostView rejectPayment(UUID boostId, String reason) {
        CurrentUser.requireRole("ADMIN");
        requireReason(reason);
        Boost b = requireBoost(boostId);
        if (b.getStatus() != BoostStatus.PENDING_PAYMENT) {
            throw new BadRequestException("Only a boost waiting for payment can have its payment rejected.");
        }
        Map<String, Object> before = snapshot(b);
        b.setStatus(BoostStatus.REJECTED);
        b.setRejectionReason(reason.strip());
        boostRepository.save(b);
        audit(b, "BOOST_PAYMENT_REJECTED", reason.strip(), before);
        return view(b, false);
    }

    @Transactional
    public BoostView approve(UUID boostId, String note) {
        CommunityModerationService.requireStaff();
        Boost b = requireBoost(boostId);
        if (b.getStatus() != BoostStatus.PENDING_REVIEW) {
            throw new BadRequestException("Only a paid boost waiting for review can be approved.");
        }
        if (b.getPaymentVerifiedAt() == null) {
            throw new BadRequestException("Payment hasn't been verified yet.");
        }
        Map<String, Object> before = snapshot(b);
        activate(b);
        boostRepository.save(b);
        audit(b, "BOOST_APPROVED", blank(note, "Approved"), before);
        return view(b, false);
    }

    @Transactional
    public BoostView reject(UUID boostId, String reason) {
        CommunityModerationService.requireStaff();
        requireReason(reason);
        Boost b = requireBoost(boostId);
        if (b.getStatus() != BoostStatus.PENDING_REVIEW && b.getStatus() != BoostStatus.PENDING_PAYMENT) {
            throw new BadRequestException("Only a boost waiting for review can be rejected — end or refund a live one instead.");
        }
        Map<String, Object> before = snapshot(b);
        b.setStatus(BoostStatus.REJECTED);
        b.setRejectionReason(reason.strip() + (b.getPaymentVerifiedAt() != null ? " (paid — refund due)" : ""));
        boostRepository.save(b);
        audit(b, "BOOST_REJECTED", reason.strip(), before);
        return view(b, false);
    }

    @Transactional
    public BoostView adminPause(UUID boostId, boolean pause, String reason) {
        CommunityModerationService.requireStaff();
        Boost b = requireBoost(boostId);
        if (pause ? b.getStatus() != BoostStatus.ACTIVE : b.getStatus() != BoostStatus.PAUSED) {
            throw new BadRequestException(pause ? "Only a running boost can be paused." : "Only a paused boost can be resumed.");
        }
        Map<String, Object> before = snapshot(b);
        b.setStatus(pause ? BoostStatus.PAUSED : BoostStatus.ACTIVE);
        boostRepository.save(b);
        audit(b, pause ? "BOOST_PAUSED" : "BOOST_RESUMED", blank(reason, pause ? "Paused by staff" : "Resumed by staff"), before);
        return view(b, false);
    }

    @Transactional
    public BoostView end(UUID boostId, String reason) {
        CommunityModerationService.requireStaff();
        requireReason(reason);
        Boost b = requireBoost(boostId);
        if (b.getStatus() != BoostStatus.ACTIVE && b.getStatus() != BoostStatus.PAUSED) {
            throw new BadRequestException("Only a running or paused boost can be ended.");
        }
        Map<String, Object> before = snapshot(b);
        b.setStatus(BoostStatus.ENDED);
        b.setEndAt(Instant.now().isAfter(b.getStartAt()) ? Instant.now() : b.getStartAt().plusSeconds(1));
        boostRepository.save(b);
        audit(b, "BOOST_ENDED", reason.strip(), before);
        return view(b, false);
    }

    @Transactional
    public BoostView refund(UUID boostId, String reason) {
        CurrentUser.requireRole("ADMIN");
        requireReason(reason);
        Boost b = requireBoost(boostId);
        if (b.getPaymentVerifiedAt() == null) {
            throw new BadRequestException("Nothing to refund — this boost's payment was never verified.");
        }
        if (b.getStatus() == BoostStatus.REFUNDED) {
            throw new BadRequestException("Already refunded.");
        }
        Map<String, Object> before = snapshot(b);
        b.setStatus(BoostStatus.REFUNDED);
        b.setRefundReason(reason.strip());
        if (b.getEndAt().isAfter(Instant.now()) && Instant.now().isAfter(b.getStartAt())) {
            b.setEndAt(Instant.now());
        }
        boostRepository.save(b);
        audit(b, "BOOST_REFUNDED", reason.strip(), before);
        return view(b, false);
    }

    @Transactional
    public BoostView changeTargeting(UUID boostId, TargetingRequest req, String reason) {
        CurrentUser.requireRole("ADMIN");
        Boost b = requireBoost(boostId);
        if (!OPEN.contains(b.getStatus())) {
            throw new BadRequestException("Targeting can only be changed on an open boost.");
        }
        BoostPackage pkg = packageRepository.findById(b.getPackageId()).orElse(null);
        Business business = businessRepository.findById(b.getBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        Map<String, Object> before = snapshot(b);
        applyTargeting(b, business, req.targetAreaIds(), req.radiusKm(), pkg == null ? 10 : pkg.getMaxRadiusKm());
        boostRepository.save(b);
        audit(b, "BOOST_RETARGETED", blank(reason, "Targeting changed"), before);
        return view(b, false);
    }

    @Transactional(readOnly = true)
    public List<BoostView> adminList(Collection<BoostStatus> statuses) {
        CommunityModerationService.requireStaff();
        return boostRepository.findByStatusInOrderByCreatedAtDesc(statuses).stream().map(b -> view(b, false)).toList();
    }

    /** Scheduled: close boosts whose window has passed. */
    @Transactional
    public int endFinished() {
        List<Boost> done = boostRepository.findFinished(EnumSet.of(BoostStatus.ACTIVE, BoostStatus.PAUSED), Instant.now());
        for (Boost b : done) {
            b.setStatus(BoostStatus.ENDED);
            boostRepository.save(b);
            auditLogService.recordSystem("BOOST", b.getId(), "BOOST_ENDED", "Boost reached its end date",
                    Map.of("status", "ACTIVE"), Map.of("status", "ENDED"));
        }
        return done.size();
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    /** Goes live now; a boost approved after its planned start keeps its full duration. */
    private void activate(Boost b) {
        Instant now = Instant.now();
        if (b.getStartAt().isBefore(now)) {
            Duration length = Duration.between(b.getStartAt(), b.getEndAt());
            b.setStartAt(now);
            b.setEndAt(now.plus(length));
        }
        b.setStatus(BoostStatus.ACTIVE);
        b.setApprovedBy(CurrentUser.idOrNull());
    }

    private void applyTargeting(Boost boost, Business business, List<UUID> areaIds, Integer radiusKm, int maxRadiusKm) {
        List<UUID> areas = areaIds == null ? List.of() : areaIds.stream().filter(Objects::nonNull).distinct().toList();
        if (!areas.isEmpty() && radiusKm != null) {
            throw new BadRequestException("Target by areas OR by distance, not both.");
        }
        if (areas.isEmpty() && radiusKm == null) {
            throw new BadRequestException("Choose target areas or a distance around your business.");
        }
        if (!areas.isEmpty()) {
            if (areas.size() > MAX_TARGET_AREAS) {
                throw new BadRequestException("Pick at most " + MAX_TARGET_AREAS + " areas.");
            }
            if (areaRepository.findAllById(areas).size() != areas.size()) {
                throw new BadRequestException("One of the chosen areas doesn't exist.");
            }
            boost.setTargetAreaIds(new ArrayList<>(areas));
            boost.setCenterLat(null);
            boost.setCenterLng(null);
            boost.setRadiusKm(null);
        } else {
            if (!RADIUS_OPTIONS_KM.contains(radiusKm)) {
                throw new BadRequestException("Distance must be 2, 5 or 10 km.");
            }
            if (radiusKm > maxRadiusKm) {
                throw new BadRequestException("This package reaches at most " + maxRadiusKm + " km.");
            }
            if (business.getLocation() == null) {
                throw new BadRequestException("Add your business location on the map before targeting by distance.");
            }
            boost.setTargetAreaIds(new ArrayList<>());
            boost.setCenterLat(business.getLocation().getY());
            boost.setCenterLng(business.getLocation().getX());
            boost.setRadiusKm(radiusKm);
        }
    }

    private Boost requireOwnedBoost(UUID userId, UUID boostId) {
        Boost b = requireBoost(boostId);
        access.requireOwned(userId, b.getBusinessId());
        return b;
    }

    private Boost requireBoost(UUID boostId) {
        return boostRepository.findById(boostId).orElseThrow(() -> new ResourceNotFoundException("Boost not found"));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("A reason is required.");
        }
    }

    private static String blank(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.strip();
    }

    private void audit(Boost b, String action, String reason, Map<String, Object> before) {
        auditLogService.record("BOOST", b.getId(), action, reason, before, snapshot(b));
    }

    private static Map<String, Object> snapshot(Boost b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", b.getStatus().name());
        m.put("startAt", String.valueOf(b.getStartAt()));
        m.put("endAt", String.valueOf(b.getEndAt()));
        m.put("targetAreaIds", b.getTargetAreaIds().toString());
        m.put("radiusKm", b.getRadiusKm());
        m.put("paymentRef", b.getPaymentRef());
        m.put("paidAmount", b.getPaidAmount());
        return m;
    }

    BoostView view(Boost b, boolean includeInstructions) {
        List<String> areaNames = b.getTargetAreaIds().isEmpty() ? List.of()
                : areaRepository.findAllById(b.getTargetAreaIds()).stream().map(a -> a.getName()).toList();
        String businessName = businessRepository.findById(b.getBusinessId()).map(Business::getName).orElse(null);
        return new BoostView(b.getId(), b.getPostId(), b.getBusinessId(), businessName, b.getPackageName(), b.getStatus(),
                b.getTargetAreaIds(), areaNames, b.getRadiusKm(), b.getStartAt(), b.getEndAt(), b.getPriceBdt(),
                b.getPaymentMethod(), b.getPaymentRef(), b.getPaymentSubmittedAt(), b.getPaymentVerifiedAt(), b.getPaidAmount(),
                b.getEstImpressions(), b.getImpressionsServed(), b.getRejectionReason(), b.getRefundReason(), b.getCreatedAt(),
                includeInstructions && b.getStatus() == BoostStatus.PENDING_PAYMENT ? gateway.instructions(b) : null);
    }
}
