package com.bdreview.platform.commerce;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.catalog.ServiceOffering;
import com.bdreview.platform.catalog.ServiceOfferingRepository;
import com.bdreview.platform.catalog.ServiceSection;
import com.bdreview.platform.catalog.StaffServiceLinkRepository;
import com.bdreview.platform.catalog.TeamMember;
import com.bdreview.platform.catalog.TeamMemberRepository;
import com.bdreview.platform.commerce.AvailabilityService.WorkWindow;
import com.bdreview.platform.commerce.CommerceRequests.PlaceBookingRequest;
import com.bdreview.platform.commerce.CommerceResponses.BookingResponse;
import com.bdreview.platform.commerce.CommerceResponses.BookingStatusEventResponse;
import com.bdreview.platform.commerce.CommerceResponses.QueueStatusResponse;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ConflictException;
import com.bdreview.platform.common.CurrentUser;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.PageRequestDefaults;
import com.bdreview.platform.common.ResourceNotFoundException;
import com.bdreview.platform.notification.NotificationType;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Phase C — appointment booking, backed by a real availability engine
 * (Stage 1). The customer proposes a service + a specific staff member (or
 * "any available staff") + a date/time; {@link AvailabilityService} generates
 * the real slots and this class re-validates and places the booking. Two
 * customers can never end up with an overlapping confirmed/pending booking
 * for the same staff member — that is enforced at the database level by the
 * {@code no_staff_double_booking} exclusion constraint (see V27), not just in
 * application code, since the frontend's availability view is only advisory.
 */
@Service
public class BookingService {

    private final BookingRepository bookingRepo;
    private final BookingStatusEventRepository eventRepo;
    private final ServiceOfferingRepository serviceRepo;
    private final TeamMemberRepository teamRepo;
    private final StaffServiceLinkRepository staffServiceRepo;
    private final AvailabilityService availability;
    private final CommerceSettingsService settingsService;
    private final CommerceGuard guard;
    private final CommerceNotifier notifier;
    private final BookingService self;

    public BookingService(BookingRepository bookingRepo, BookingStatusEventRepository eventRepo,
                          ServiceOfferingRepository serviceRepo, TeamMemberRepository teamRepo,
                          StaffServiceLinkRepository staffServiceRepo, AvailabilityService availability,
                          CommerceSettingsService settingsService, CommerceGuard guard, CommerceNotifier notifier,
                          @Lazy BookingService self) {
        this.bookingRepo = bookingRepo;
        this.eventRepo = eventRepo;
        this.serviceRepo = serviceRepo;
        this.teamRepo = teamRepo;
        this.staffServiceRepo = staffServiceRepo;
        this.availability = availability;
        this.settingsService = settingsService;
        this.guard = guard;
        this.notifier = notifier;
        this.self = self;
    }

    public BookingResponse placeBooking(UUID customerUserId, UUID businessId, PlaceBookingRequest req) {
        Business business = guard.getLiveOrThrow(businessId);
        BusinessCommerceSettings settings = settingsService.requireBookingLive(businessId);

        ServiceOffering svc = serviceRepo.findById(req.serviceId())
                .filter(s -> s.getBusinessId().equals(businessId) && s.getSection() == ServiceSection.OFFERING)
                .orElseThrow(() -> new BadRequestException("That service is not available for booking."));

        if (req.preferredDate().isBefore(LocalDate.now())) {
            throw new BadRequestException("Preferred date can't be in the past.");
        }

        // The no_staff_double_booking DB constraint only protects one staff member's
        // calendar — nothing stops the same customer from booking themselves into two
        // overlapping appointments (possibly with different staff, or at different
        // businesses entirely). Checked against this service's own base duration since
        // the actual staff candidate isn't picked yet.
        int precheckOccupied = (svc.getDurationMinutes() != null ? svc.getDurationMinutes() : 60)
                + (svc.getBufferMinutes() != null ? svc.getBufferMinutes() : 0);
        LocalDateTime precheckStart = LocalDateTime.of(req.preferredDate(), req.preferredTime());
        LocalDateTime precheckEnd = precheckStart.plusMinutes(precheckOccupied);
        if (!bookingRepo.findOverlappingForCustomer(customerUserId, precheckStart, precheckEnd).isEmpty()) {
            throw new ConflictException("You already have a booking around this time.");
        }

        List<UUID> candidates;
        if (req.staffId() != null) {
            TeamMember staff = teamRepo.findById(req.staffId())
                    .filter(t -> t.getBusinessId().equals(businessId) && t.isActive())
                    .orElseThrow(() -> new BadRequestException("That staff member is not available."));
            if (!staffServiceRepo.findServiceIdsByTeamMemberId(staff.getId()).contains(svc.getId())) {
                throw new BadRequestException("That staff member does not provide this service.");
            }
            candidates = List.of(staff.getId());
        } else {
            candidates = availability.qualifiedActiveStaffIds(businessId, svc.getId());
            if (candidates.isEmpty()) {
                throw new BadRequestException("No staff are currently available for this service.");
            }
        }

        boolean anyFitsWindow = false;
        for (UUID staffId : candidates) {
            // Resolved per candidate — a staff member's own duration/buffer override
            // (if any) determines the window they actually need, and is what gets
            // snapshotted onto the booking if this candidate is the one that wins.
            int duration = availability.resolveDuration(staffId, svc.getId(), svc);
            int buffer = availability.resolveBuffer(staffId, svc.getId(), svc);
            int occupied = duration + buffer;
            LocalDateTime slotStart = LocalDateTime.of(req.preferredDate(), req.preferredTime());
            LocalDateTime slotEnd = slotStart.plusMinutes(occupied);

            Optional<WorkWindow> window = availability.workingWindow(staffId, req.preferredDate());
            if (window.isEmpty() || !availability.fitsWindow(window.get(), req.preferredTime(), occupied)) {
                continue;
            }
            anyFitsWindow = true;
            if (!availability.isFree(staffId, slotStart, slotEnd)) {
                continue; // pre-check only — the DB constraint below is the real guarantee
            }
            try {
                TeamMember staff = teamRepo.findById(staffId).orElseThrow();
                Booking saved = self.attemptPlacement(customerUserId, business, svc, staff, req,
                        duration, buffer, slotStart, slotEnd, settings.isAutoConfirmBookings());
                return afterPlacement(business, saved, settings.isAutoConfirmBookings());
            } catch (DataAccessException ex) {
                if (!isSlotConflict(ex)) {
                    throw ex;
                }
                // someone else just took this staff member's slot — try the next candidate, if any.
                // Under true concurrency Postgres can report this either as the
                // no_staff_double_booking exclusion violation or (depending on
                // exactly how the two inserts interleave) a plain deadlock — both
                // mean the same thing here: this attempt lost the race.
            }
        }

        if (req.staffId() != null) {
            throw anyFitsWindow
                    ? new ConflictException("This time slot was just booked. Please choose another time.")
                    : new BadRequestException("That time is outside this staff member's working hours.");
        }
        throw new BadRequestException("No staff are currently available for this service at that time.");
    }

    /** One insert attempt in its own transaction so a lost race (caught by the caller) never poisons a shared transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Booking attemptPlacement(UUID customerUserId, Business business, ServiceOffering svc, TeamMember staff,
                                    PlaceBookingRequest req, int duration, int buffer,
                                    LocalDateTime slotStart, LocalDateTime slotEnd, boolean autoConfirm) {
        BookingStatus initialStatus = autoConfirm ? BookingStatus.CONFIRMED : BookingStatus.PENDING;
        Booking booking = bookingRepo.save(Booking.builder()
                .businessId(business.getId())
                .customerUserId(customerUserId)
                .bookingNumber("B-" + bookingRepo.nextBookingNumber())
                .status(initialStatus)
                .serviceId(svc.getId())
                .serviceNameSnapshot(svc.getName())
                .staffId(staff.getId())
                .staffNameSnapshot(staff.getName())
                .preferredDate(req.preferredDate())
                .preferredTime(req.preferredTime())
                .durationMinutesSnapshot(duration)
                .bufferMinutesSnapshot(buffer)
                .slotStart(slotStart)
                .slotEnd(slotEnd)
                .autoConfirmed(autoConfirm)
                .customerNameSnapshot(req.customerName().trim())
                .customerPhoneSnapshot(req.customerPhone().trim())
                .customerNote(blankToNull(req.customerNote()))
                .build());

        eventRepo.save(BookingStatusEvent.builder()
                .bookingId(booking.getId()).fromStatus(null).toStatus(initialStatus)
                .actorUserId(customerUserId).note(autoConfirm ? "Booking requested (auto-confirmed)" : "Booking requested")
                .build());
        return booking;
    }

    private BookingResponse afterPlacement(Business business, Booking booking, boolean autoConfirm) {
        notifier.newBooking(business.getOwnerUserId(), booking.getId(), booking.getBookingNumber(),
                booking.getCustomerNameSnapshot());
        if (autoConfirm) {
            notifier.bookingStatusChanged(booking.getCustomerUserId(), booking.getId(), booking.getBookingNumber(),
                    BookingStatus.CONFIRMED, NotificationType.BOOKING_CONFIRMED);
        }
        String note = autoConfirm ? "Booking requested (auto-confirmed)" : "Booking requested";
        return toResponse(booking, business, List.of(
                new BookingStatusEventResponse(null, booking.getStatus(), note, booking.getCreatedAt())));
    }

    /**
     * A genuine lost race on {@code no_staff_double_booking}, not a bug: either the
     * exclusion constraint itself (SQLState 23P01) or — depending on exactly how two
     * concurrent inserts into the same GiST index interleave — a plain deadlock
     * (40P01) between the two racing transactions.
     */
    private static boolean isSlotConflict(DataAccessException ex) {
        Throwable cause = ex;
        while (cause != null) {
            String msg = cause.getMessage();
            if (msg != null && (msg.contains("no_staff_double_booking") || msg.contains("deadlock detected"))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @Transactional(readOnly = true)
    public Page<BookingResponse> myBookings(UUID customerUserId, int page, int size) {
        Page<Booking> bookings = bookingRepo.findByCustomerUserIdOrderByCreatedAtDesc(
                customerUserId, PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size)));
        return mapPage(bookings);
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(UUID requesterUserId, UUID bookingId) {
        Booking booking = bookingRepo.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        Business business = guard.getLiveOrThrow(booking.getBusinessId());
        boolean isOwnerOrAdmin = business.getOwnerUserId().equals(requesterUserId) || CurrentUser.hasRole("ADMIN");
        boolean allowed = booking.getCustomerUserId().equals(requesterUserId) || isOwnerOrAdmin;
        if (!allowed) {
            throw new ForbiddenException("You cannot view this booking.");
        }
        return toResponse(booking, business, timeline(bookingId));
    }

    @Transactional
    public BookingResponse customerCancel(UUID customerUserId, UUID bookingId) {
        Booking booking = bookingRepo.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (!booking.getCustomerUserId().equals(customerUserId)) {
            throw new ForbiddenException("You cannot cancel this booking.");
        }
        if (booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BadRequestException("This booking can no longer be cancelled.");
        }
        applyTransition(booking, BookingStatus.CANCELLED, customerUserId, "Cancelled by customer");
        Business business = guard.getLiveOrThrow(booking.getBusinessId());
        return toResponse(booking, business, timeline(bookingId));
    }

    @Transactional(readOnly = true)
    public Page<BookingResponse> ownerBookings(UUID ownerUserId, UUID businessId, BookingStatus status, int page, int size) {
        guard.getOwnedOrThrow(ownerUserId, businessId);
        PageRequest pr = PageRequest.of(Math.max(page, 0), PageRequestDefaults.clamp(size));
        Page<Booking> bookings = status == null
                ? bookingRepo.findByBusinessIdOrderByPreferredDateAscPreferredTimeAsc(businessId, pr)
                : bookingRepo.findByBusinessIdAndStatusOrderByPreferredDateAscPreferredTimeAsc(businessId, status, pr);
        return mapPage(bookings);
    }

    @Transactional(readOnly = true)
    public long pendingCount(UUID ownerUserId, UUID businessId) {
        guard.getOwnedOrThrow(ownerUserId, businessId);
        return bookingRepo.countByBusinessIdAndStatus(businessId, BookingStatus.PENDING);
    }

    @Transactional
    public BookingResponse transition(UUID ownerUserId, UUID bookingId, BookingStatus target, String note) {
        Booking booking = bookingRepo.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        Business business = guard.getOwnedOrThrow(ownerUserId, booking.getBusinessId());

        BookingStatus from = booking.getStatus();
        if (!from.canTransitionTo(target)) {
            throw new BadRequestException("Can't move a booking from " + human(from) + " to " + human(target) + ".");
        }
        if (target == BookingStatus.REJECTED) {
            booking.setRejectionReason(blankToNull(note));
        }
        applyTransition(booking, target, ownerUserId, note);

        NotificationType type = switch (target) {
            case CONFIRMED -> NotificationType.BOOKING_CONFIRMED;
            case REJECTED -> NotificationType.BOOKING_REJECTED;
            default -> NotificationType.BOOKING_STATUS_CHANGED;
        };
        notifier.bookingStatusChanged(booking.getCustomerUserId(), booking.getId(), booking.getBookingNumber(), target, type);

        return toResponse(booking, business, timeline(bookingId));
    }

    /**
     * Owner marks a CONFIRMED booking as actually under way — live-queue metadata,
     * not a status change (still CONFIRMED until {@link #transition} to COMPLETED).
     * At most one booking per staff member may be "in progress" at a time.
     */
    @Transactional
    public BookingResponse start(UUID ownerUserId, UUID bookingId) {
        Booking booking = bookingRepo.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        Business business = guard.getOwnedOrThrow(ownerUserId, booking.getBusinessId());
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BadRequestException("Only a confirmed booking can be started.");
        }
        if (booking.getStaffId() != null) {
            bookingRepo.findByStaffIdAndPreferredDateAndStatusOrderBySlotStartAsc(
                            booking.getStaffId(), booking.getPreferredDate(), BookingStatus.CONFIRMED)
                    .stream()
                    .filter(b -> b.getStartedAt() != null && !b.getId().equals(booking.getId()))
                    .findFirst()
                    .ifPresent(running -> {
                        throw new BadRequestException(booking.getStaffNameSnapshot() + " is already serving "
                                + running.getCustomerNameSnapshot() + " (" + running.getBookingNumber()
                                + "). Mark that one completed first.");
                    });
        }
        booking.setStartedAt(Instant.now());
        bookingRepo.save(booking);
        return toResponse(booking, business, timeline(bookingId));
    }

    /**
     * Live queue position/ETA for a CONFIRMED booking today, grounded in each
     * ahead booking's own snapshotted duration — a currently in-progress one
     * contributes its remaining time, everything else its full duration.
     */
    @Transactional(readOnly = true)
    public QueueStatusResponse queueStatus(UUID requesterUserId, UUID bookingId) {
        Booking booking = bookingRepo.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        Business business = guard.getLiveOrThrow(booking.getBusinessId());
        boolean allowed = booking.getCustomerUserId().equals(requesterUserId)
                || business.getOwnerUserId().equals(requesterUserId) || CurrentUser.hasRole("ADMIN");
        if (!allowed) {
            throw new ForbiddenException("You cannot view this booking.");
        }
        if (booking.getStatus() != BookingStatus.CONFIRMED || booking.getStaffId() == null
                || !booking.getPreferredDate().isEqual(LocalDate.now())) {
            return QueueStatusResponse.notApplicable();
        }

        List<Booking> ahead = bookingRepo
                .findByStaffIdAndPreferredDateAndStatusOrderBySlotStartAsc(
                        booking.getStaffId(), booking.getPreferredDate(), BookingStatus.CONFIRMED)
                .stream()
                .filter(b -> b.getSlotStart().isBefore(booking.getSlotStart()))
                .toList();

        int estimatedWaitMinutes = 0;
        String currentlyServing = null;
        for (Booking b : ahead) {
            int total = b.getDurationMinutesSnapshot() + b.getBufferMinutesSnapshot();
            if (b.getStartedAt() != null) {
                long elapsed = Duration.between(b.getStartedAt(), Instant.now()).toMinutes();
                estimatedWaitMinutes += Math.max(0, total - (int) elapsed);
                currentlyServing = b.getServiceNameSnapshot();
            } else {
                estimatedWaitMinutes += total;
            }
        }
        return new QueueStatusResponse(true, ahead.size(), estimatedWaitMinutes, currentlyServing);
    }

    /**
     * Sweeps CONFIRMED bookings whose slot ended over an hour ago (a grace window so an
     * owner running slightly behind can still mark one COMPLETED by hand) and auto-closes
     * them — COMPLETED if the owner had started serving it (see {@link #start}), NO_SHOW
     * otherwise. Without this, a booking the owner forgot to close sits "Confirmed"
     * forever, which is misleading for both sides after the appointment time has passed.
     */
    @Transactional
    @Scheduled(fixedRate = 15, timeUnit = TimeUnit.MINUTES)
    public void autoExpirePastBookings() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(1);
        for (Booking booking : bookingRepo.findByStatusAndSlotEndBefore(BookingStatus.CONFIRMED, cutoff)) {
            BookingStatus target = booking.getStartedAt() != null ? BookingStatus.COMPLETED : BookingStatus.NO_SHOW;
            String note = target == BookingStatus.COMPLETED
                    ? "Auto-completed — booking time passed"
                    : "Auto-marked no-show — booking time passed without being started";
            applyTransition(booking, target, null, note);
            notifier.bookingStatusChanged(booking.getCustomerUserId(), booking.getId(), booking.getBookingNumber(),
                    target, NotificationType.BOOKING_STATUS_CHANGED);
        }
    }

    // ================================================================
    // Helpers
    // ================================================================
    private void applyTransition(Booking booking, BookingStatus target, UUID actor, String note) {
        BookingStatus from = booking.getStatus();
        booking.setStatus(target);
        bookingRepo.save(booking);
        eventRepo.save(BookingStatusEvent.builder()
                .bookingId(booking.getId()).fromStatus(from).toStatus(target)
                .actorUserId(actor).note(blankToNull(note)).build());
    }

    private List<BookingStatusEventResponse> timeline(UUID bookingId) {
        return eventRepo.findByBookingIdOrderByCreatedAtAsc(bookingId).stream()
                .map(BookingStatusEventResponse::from).toList();
    }

    private Page<BookingResponse> mapPage(Page<Booking> bookings) {
        Map<UUID, Business> businessCache = new HashMap<>();
        return bookings.map(b -> {
            Business biz = businessCache.computeIfAbsent(b.getBusinessId(), guard::getLiveOrThrow);
            return toResponse(b, biz, null);
        });
    }

    private BookingResponse toResponse(Booking b, Business business, List<BookingStatusEventResponse> timeline) {
        return new BookingResponse(
                b.getId(), b.getBookingNumber(), b.getBusinessId(), business.getName(), business.getSlug(),
                b.getCustomerUserId(), b.getStatus(), b.getServiceId(), b.getServiceNameSnapshot(),
                b.getStaffId(), b.getStaffNameSnapshot(), b.getPreferredDate(), b.getPreferredTime(),
                b.getCustomerNameSnapshot(), b.getCustomerPhoneSnapshot(), b.getCustomerNote(),
                b.getRejectionReason(), b.getCreatedAt(), timeline, b.isAutoConfirmed(), b.getStartedAt());
    }

    private static String human(BookingStatus s) {
        return s.name().toLowerCase().replace('_', ' ');
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
