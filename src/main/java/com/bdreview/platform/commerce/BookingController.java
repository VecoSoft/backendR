package com.bdreview.platform.commerce;

import com.bdreview.platform.commerce.AvailabilityService.AvailabilityResponse;
import com.bdreview.platform.commerce.CommerceRequests.PlaceBookingRequest;
import com.bdreview.platform.commerce.CommerceRequests.UpdateBookingStatusRequest;
import com.bdreview.platform.commerce.CommerceResponses.BookingResponse;
import com.bdreview.platform.commerce.CommerceResponses.QueueStatusResponse;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Bookings (Phase C). Placement and the customer's own history/cancel live
 * under {@code /api/v1/bookings*}; the owner's per-business queue lives under
 * {@code /api/v1/businesses/{id}/bookings}. All endpoints require authentication.
 */
@RestController
@RequestMapping("/api/v1")
public class BookingController {

    private final BookingService bookings;
    private final AvailabilityService availability;
    private final com.bdreview.platform.promo.PromoEventService promoEvents;

    public BookingController(BookingService bookings, AvailabilityService availability,
                             com.bdreview.platform.promo.PromoEventService promoEvents) {
        this.bookings = bookings;
        this.availability = availability;
        this.promoEvents = promoEvents;
    }

    // ---- customer ----------------------------------------------------

    @PostMapping("/businesses/{businessId}/bookings")
    public BookingResponse place(@PathVariable UUID businessId, @Valid @RequestBody PlaceBookingRequest req,
                                 // V58 promo attribution — set when the booking came from a business post / share link
                                 @RequestParam(required = false) UUID promoPostId,
                                 @RequestParam(required = false) UUID promoBoostId,
                                 @RequestParam(required = false) String promoRef) {
        BookingResponse booking = bookings.placeBooking(CurrentUser.id(), businessId, req);
        promoEvents.recordConversion(new com.bdreview.platform.promo.PromoEventService.Attribution(promoPostId, promoBoostId, promoRef),
                com.bdreview.platform.promo.PromoEnums.PromoEventType.BOOKING, booking.id(), businessId);
        return booking;
    }

    /** Public — the real slot engine (Stage 1). {@code staffId} omitted means "any available staff". Advisory only: the backend re-checks at placement. */
    @GetMapping("/businesses/{businessId}/bookings/availability")
    public AvailabilityResponse availability(@PathVariable UUID businessId,
                                             @RequestParam UUID serviceId,
                                             @RequestParam(required = false) UUID staffId,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return availability.availability(businessId, serviceId, staffId, date);
    }

    @GetMapping("/bookings/mine")
    public PageResponse<BookingResponse> mine(@RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(bookings.myBookings(CurrentUser.id(), page, size));
    }

    @GetMapping("/bookings/{id}")
    public BookingResponse get(@PathVariable UUID id) {
        return bookings.getBooking(CurrentUser.id(), id);
    }

    @PostMapping("/bookings/{id}/cancel")
    public BookingResponse cancel(@PathVariable UUID id) {
        return bookings.customerCancel(CurrentUser.id(), id);
    }

    /** Customer or owner — live queue position/ETA for a CONFIRMED booking today; {@code applicable=false} otherwise. */
    @GetMapping("/bookings/{id}/queue-status")
    public QueueStatusResponse queueStatus(@PathVariable UUID id) {
        return bookings.queueStatus(CurrentUser.id(), id);
    }

    // ---- owner -----------------------------------------------------

    @GetMapping("/businesses/{businessId}/bookings")
    public PageResponse<BookingResponse> ownerList(@PathVariable UUID businessId,
                                                    @RequestParam(required = false) BookingStatus status,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(bookings.ownerBookings(CurrentUser.id(), businessId, status, page, size));
    }

    @GetMapping("/businesses/{businessId}/bookings/pending-count")
    public long pendingCount(@PathVariable UUID businessId) {
        return bookings.pendingCount(CurrentUser.id(), businessId);
    }

    @PatchMapping("/bookings/{id}/status")
    public BookingResponse setStatus(@PathVariable UUID id, @Valid @RequestBody UpdateBookingStatusRequest req) {
        return bookings.transition(CurrentUser.id(), id, req.status(), req.note());
    }

    /** Owner marks a CONFIRMED booking as actually under way — live-queue metadata, not a status change. */
    @PostMapping("/bookings/{id}/start")
    public BookingResponse start(@PathVariable UUID id) {
        return bookings.start(CurrentUser.id(), id);
    }
}
