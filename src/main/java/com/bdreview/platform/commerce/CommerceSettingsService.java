package com.bdreview.platform.commerce;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.CategoryKind;
import com.bdreview.platform.commerce.CommerceRequests.AcceptingOrdersRequest;
import com.bdreview.platform.commerce.CommerceRequests.BookingSettingsRequest;
import com.bdreview.platform.commerce.CommerceRequests.CommerceSettingsRequest;
import com.bdreview.platform.commerce.CommerceResponses.CommerceSettingsResponse;
import com.bdreview.platform.commerce.CommerceResponses.PublicCommerceView;
import com.bdreview.platform.common.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Per-business commerce configuration. There is no row until the owner turns
 * something on — reads fall back to a transient SHOWCASE_ONLY default so the
 * public page and every existing listing behave exactly as before.
 */
@Service
public class CommerceSettingsService {

    private final BusinessCommerceSettingsRepository repo;
    private final DeliveryZoneRepository zoneRepo;
    private final CommerceGuard guard;

    public CommerceSettingsService(BusinessCommerceSettingsRepository repo,
                                   DeliveryZoneRepository zoneRepo,
                                   CommerceGuard guard) {
        this.repo = repo;
        this.zoneRepo = zoneRepo;
        this.guard = guard;
    }

    @Transactional(readOnly = true)
    public BusinessCommerceSettings getOrDefault(UUID businessId) {
        return repo.findById(businessId).orElseGet(() -> BusinessCommerceSettings.defaultsFor(businessId));
    }

    @Transactional(readOnly = true)
    public CommerceSettingsResponse ownerView(UUID ownerUserId, UUID businessId) {
        guard.getOwnedOrThrow(ownerUserId, businessId);
        return CommerceSettingsResponse.from(getOrDefault(businessId), zoneRepo.countByBusinessId(businessId) > 0);
    }

    @Transactional(readOnly = true)
    public PublicCommerceView publicView(UUID businessId) {
        return PublicCommerceView.from(getOrDefault(businessId), zoneRepo.existsByBusinessIdAndActiveTrue(businessId));
    }

    @Transactional
    public CommerceSettingsResponse upsert(UUID ownerUserId, UUID businessId, CommerceSettingsRequest req) {
        Business business = guard.getOwnedOrThrow(ownerUserId, businessId);

        boolean wantsOrdering = req.mode() == CommerceMode.DIRECT_ORDER && req.orderingEnabled();
        if (wantsOrdering) {
            if (business.getCategory().getKind() != CategoryKind.RESTAURANT) {
                throw new BadRequestException("Direct ordering is only available for Restaurant & Food listings right now.");
            }
            if (!req.pickupEnabled() && !req.ownDeliveryEnabled()) {
                throw new BadRequestException("Enable at least one fulfilment option: pickup or own delivery.");
            }
        }
        if (req.mode() == CommerceMode.DIRECT_ORDER && !req.paymentCashOnDelivery() && !req.paymentPayAtBusiness()) {
            throw new BadRequestException("Enable at least one payment option.");
        }

        BusinessCommerceSettings s = repo.findById(businessId)
                .orElseGet(() -> BusinessCommerceSettings.defaultsFor(businessId));
        s.setMode(req.mode());
        s.setOrderingEnabled(wantsOrdering);
        s.setPickupEnabled(req.pickupEnabled());
        s.setOwnDeliveryEnabled(req.ownDeliveryEnabled());
        s.setPaymentCashOnDelivery(req.paymentCashOnDelivery());
        s.setPaymentPayAtBusiness(req.paymentPayAtBusiness());
        s.setDefaultPrepMinutes(req.defaultPrepMinutes());
        repo.save(s);
        return CommerceSettingsResponse.from(s, zoneRepo.countByBusinessId(businessId) > 0);
    }

    @Transactional
    public CommerceSettingsResponse setAccepting(UUID ownerUserId, UUID businessId, AcceptingOrdersRequest req) {
        guard.getOwnedOrThrow(ownerUserId, businessId);
        BusinessCommerceSettings s = repo.findById(businessId)
                .orElseGet(() -> BusinessCommerceSettings.defaultsFor(businessId));
        s.setAcceptingOrders(req.accepting());
        s.setPauseReason(req.accepting() ? null : blankToNull(req.reason()));
        repo.save(s);
        return CommerceSettingsResponse.from(s, zoneRepo.countByBusinessId(businessId) > 0);
    }

    /** Read used by OrderService — asserts ordering is actually live before a customer can place an order. */
    BusinessCommerceSettings requireOrderingLive(UUID businessId) {
        BusinessCommerceSettings s = getOrDefault(businessId);
        if (s.getMode() != CommerceMode.DIRECT_ORDER || !s.isOrderingEnabled()) {
            throw new BadRequestException("This business is not accepting online orders.");
        }
        if (!s.isAcceptingOrders()) {
            throw new BadRequestException(s.getPauseReason() != null && !s.getPauseReason().isBlank()
                    ? "Not accepting new orders: " + s.getPauseReason()
                    : "This business has paused new orders. Please try again later.");
        }
        return s;
    }

    /** Owner toggle for online booking (Phase C — currently Salon & Beauty only). */
    @Transactional
    public CommerceSettingsResponse setBooking(UUID ownerUserId, UUID businessId, BookingSettingsRequest req) {
        Business business = guard.getOwnedOrThrow(ownerUserId, businessId);
        if (req.bookingEnabled() && business.getCategory().getKind() != CategoryKind.SALON) {
            throw new BadRequestException("Online booking is only available for Salon & Beauty listings right now.");
        }
        BusinessCommerceSettings s = repo.findById(businessId)
                .orElseGet(() -> BusinessCommerceSettings.defaultsFor(businessId));
        s.setBookingEnabled(req.bookingEnabled());
        s.setAutoConfirmBookings(req.autoConfirmBookings());
        s.setMode(req.bookingEnabled() ? CommerceMode.BOOKING : CommerceMode.SHOWCASE_ONLY);
        repo.save(s);
        return CommerceSettingsResponse.from(s, zoneRepo.countByBusinessId(businessId) > 0);
    }

    /** Read used by BookingService — asserts booking is actually live before a customer can request one. */
    BusinessCommerceSettings requireBookingLive(UUID businessId) {
        BusinessCommerceSettings s = getOrDefault(businessId);
        if (s.getMode() != CommerceMode.BOOKING || !s.isBookingEnabled()) {
            throw new BadRequestException("This business is not accepting online bookings.");
        }
        if (!s.isAcceptingOrders()) {
            throw new BadRequestException(s.getPauseReason() != null && !s.getPauseReason().isBlank()
                    ? "Not accepting new bookings: " + s.getPauseReason()
                    : "This business has paused new bookings. Please try again later.");
        }
        return s;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
