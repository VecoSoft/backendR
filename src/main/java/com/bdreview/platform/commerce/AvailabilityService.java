package com.bdreview.platform.commerce;

import com.bdreview.platform.catalog.ServiceOffering;
import com.bdreview.platform.catalog.ServiceOfferingRepository;
import com.bdreview.platform.catalog.StaffServiceLink;
import com.bdreview.platform.catalog.StaffServiceLinkRepository;
import com.bdreview.platform.catalog.StaffTimeOffRepository;
import com.bdreview.platform.catalog.StaffWeeklySchedule;
import com.bdreview.platform.catalog.StaffWeeklyScheduleRepository;
import com.bdreview.platform.catalog.TeamMember;
import com.bdreview.platform.catalog.TeamMemberRepository;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The real slot-generation engine (Stage 1 — no hardcoded slots). Reads a
 * staff member's weekly working window, single optional daily break, and
 * time-off, and generates candidate appointment starts stepping by the
 * service's {@code duration + buffer} so a single staff member's own
 * candidate slots never overlap each other. Existing PENDING/CONFIRMED
 * bookings are the last filter here (a friendly pre-check); the
 * {@code no_staff_double_booking} DB exclusion constraint on
 * {@code business_booking} is the actual race-proof guarantee (see V27).
 */
@Service
public class AvailabilityService {

    static final int DEFAULT_DURATION_MIN = 60;

    private final ServiceOfferingRepository serviceRepo;
    private final TeamMemberRepository teamRepo;
    private final StaffServiceLinkRepository staffServiceRepo;
    private final StaffWeeklyScheduleRepository scheduleRepo;
    private final StaffTimeOffRepository timeOffRepo;
    private final BookingRepository bookingRepo;

    public AvailabilityService(ServiceOfferingRepository serviceRepo, TeamMemberRepository teamRepo,
                               StaffServiceLinkRepository staffServiceRepo, StaffWeeklyScheduleRepository scheduleRepo,
                               StaffTimeOffRepository timeOffRepo, BookingRepository bookingRepo) {
        this.serviceRepo = serviceRepo;
        this.teamRepo = teamRepo;
        this.staffServiceRepo = staffServiceRepo;
        this.scheduleRepo = scheduleRepo;
        this.timeOffRepo = timeOffRepo;
        this.bookingRepo = bookingRepo;
    }

    public record WorkWindow(LocalTime start, LocalTime end, LocalTime breakStart, LocalTime breakEnd) {
    }

    public record AvailabilitySlot(LocalTime time, boolean available) {
    }

    public record AvailabilityResponse(LocalDate date, int durationMinutes, int bufferMinutes, List<AvailabilitySlot> slots) {
    }

    public static int resolveDuration(ServiceOffering svc) {
        Integer d = svc.getDurationMinutes();
        return d != null && d > 0 ? d : DEFAULT_DURATION_MIN;
    }

    public static int resolveBuffer(ServiceOffering svc) {
        Integer b = svc.getBufferMinutes();
        return b != null && b > 0 ? b : 0;
    }

    /** Staff-aware: that staff member's override on the qualification row wins, else the service's own default. */
    @Transactional(readOnly = true)
    public int resolveDuration(UUID staffId, UUID serviceId, ServiceOffering svc) {
        return staffServiceRepo.findById(new StaffServiceLink.Id(staffId, serviceId))
                .map(StaffServiceLink::getDurationMinutes)
                .filter(d -> d != null && d > 0)
                .orElseGet(() -> resolveDuration(svc));
    }

    @Transactional(readOnly = true)
    public int resolveBuffer(UUID staffId, UUID serviceId, ServiceOffering svc) {
        return staffServiceRepo.findById(new StaffServiceLink.Id(staffId, serviceId))
                .map(StaffServiceLink::getBufferMinutes)
                .filter(b -> b != null && b > 0)
                .orElseGet(() -> resolveBuffer(svc));
    }

    /** Active staff (business-scoped) qualified for a service, in showcase order. */
    @Transactional(readOnly = true)
    public List<UUID> qualifiedActiveStaffIds(UUID businessId, UUID serviceId) {
        Set<UUID> qualified = new HashSet<>(staffServiceRepo.findTeamMemberIdsByServiceId(serviceId));
        return teamRepo.findByBusinessIdOrderBySortOrderAsc(businessId).stream()
                .filter(TeamMember::isActive)
                .map(TeamMember::getId)
                .filter(qualified::contains)
                .toList();
    }

    /** Empty when the staff member is off that day (not scheduled, or on time-off). */
    @Transactional(readOnly = true)
    public Optional<WorkWindow> workingWindow(UUID staffId, LocalDate date) {
        if (timeOffRepo.existsOnDate(staffId, date)) {
            return Optional.empty();
        }
        return scheduleRepo.findByTeamMemberIdAndDayOfWeek(staffId, date.getDayOfWeek())
                .map(s -> new WorkWindow(s.getStartTime(), s.getEndTime(), s.getBreakStart(), s.getBreakEnd()));
    }

    /**
     * Whether a candidate start time's full occupied window fits inside the
     * working hours and skips the break. Minute-of-day integer arithmetic —
     * never wraps past midnight the way {@code LocalTime.plusMinutes} can.
     */
    public boolean fitsWindow(WorkWindow w, LocalTime start, int occupiedMinutes) {
        int startMin = minuteOfDay(start);
        int endMin = startMin + occupiedMinutes;
        if (startMin < minuteOfDay(w.start()) || endMin > minuteOfDay(w.end())) {
            return false;
        }
        return w.breakStart() == null || !rangesOverlap(startMin, endMin, minuteOfDay(w.breakStart()), minuteOfDay(w.breakEnd()));
    }

    /** No existing PENDING/CONFIRMED booking for this staff member overlaps the window — a pre-check, not the guarantee. */
    @Transactional(readOnly = true)
    public boolean isFree(UUID staffId, LocalDateTime start, LocalDateTime end) {
        return bookingRepo.findOverlapping(staffId, start, end).isEmpty();
    }

    private List<LocalTime> candidateSlotStarts(WorkWindow w, int occupiedMinutes) {
        List<LocalTime> out = new ArrayList<>();
        int windowStart = minuteOfDay(w.start());
        int windowEnd = minuteOfDay(w.end());
        int breakStart = w.breakStart() != null ? minuteOfDay(w.breakStart()) : -1;
        int breakEnd = w.breakEnd() != null ? minuteOfDay(w.breakEnd()) : -1;
        for (int t = windowStart; t + occupiedMinutes <= windowEnd; t += occupiedMinutes) {
            boolean crossesBreak = breakStart >= 0 && rangesOverlap(t, t + occupiedMinutes, breakStart, breakEnd);
            if (!crossesBreak) {
                out.add(LocalTime.ofSecondOfDay(t * 60L));
            }
        }
        return out;
    }

    private static int minuteOfDay(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

    private static boolean rangesOverlap(int aStart, int aEnd, int bStart, int bEnd) {
        return aStart < bEnd && bStart < aEnd;
    }

    /**
     * Public availability for a service on a date, for one staff member or —
     * when {@code staffId} is null — "any available staff": a slot is
     * available if at least one qualified, active staff member is free for it.
     */
    @Transactional(readOnly = true)
    public AvailabilityResponse availability(UUID businessId, UUID serviceId, UUID staffId, LocalDate date) {
        ServiceOffering svc = serviceRepo.findById(serviceId)
                .filter(s -> s.getBusinessId().equals(businessId))
                .orElseThrow(() -> new ResourceNotFoundException("Service not found"));

        List<UUID> candidates;
        if (staffId != null) {
            TeamMember staff = teamRepo.findById(staffId)
                    .filter(t -> t.getBusinessId().equals(businessId) && t.isActive())
                    .orElseThrow(() -> new BadRequestException("That staff member is not available."));
            if (!staffServiceRepo.findServiceIdsByTeamMemberId(staffId).contains(serviceId)) {
                throw new BadRequestException("That staff member does not provide this service.");
            }
            candidates = List.of(staff.getId());
        } else {
            candidates = qualifiedActiveStaffIds(businessId, serviceId);
        }

        // Displayed up front, before a specific slot (and therefore a specific
        // staff member) is chosen — an explicit staff pick shows their own
        // resolved duration; "any available staff" shows the service's plain
        // default since candidates may each resolve differently.
        int displayDuration = staffId != null ? resolveDuration(staffId, serviceId, svc) : resolveDuration(svc);
        int displayBuffer = staffId != null ? resolveBuffer(staffId, serviceId, svc) : resolveBuffer(svc);

        Map<LocalTime, Boolean> merged = new TreeMap<>();
        for (UUID sid : candidates) {
            Optional<WorkWindow> window = workingWindow(sid, date);
            if (window.isEmpty()) {
                continue;
            }
            int occupied = resolveDuration(sid, serviceId, svc) + resolveBuffer(sid, serviceId, svc);
            for (LocalTime t : candidateSlotStarts(window.get(), occupied)) {
                boolean free = isFree(sid, LocalDateTime.of(date, t), LocalDateTime.of(date, t).plusMinutes(occupied));
                merged.merge(t, free, (a, b) -> a || b);
            }
        }

        List<AvailabilitySlot> slots = merged.entrySet().stream()
                .map(e -> new AvailabilitySlot(e.getKey(), e.getValue()))
                .toList();
        return new AvailabilityResponse(date, displayDuration, displayBuffer, slots);
    }
}
