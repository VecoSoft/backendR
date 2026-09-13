package com.bdreview.platform.catalog;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.catalog.CatalogRequests.ReplaceScheduleRequest;
import com.bdreview.platform.catalog.CatalogRequests.StaffServiceAssignment;
import com.bdreview.platform.catalog.CatalogRequests.StaffServiceAssignmentsRequest;
import com.bdreview.platform.catalog.CatalogRequests.TimeOffRequest;
import com.bdreview.platform.catalog.CatalogRequests.WeeklyScheduleEntryRequest;
import com.bdreview.platform.common.BadRequestException;
import com.bdreview.platform.common.ForbiddenException;
import com.bdreview.platform.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Staff qualification (which services a staff member provides) + weekly working
 * schedule / breaks / time-off — the configuration the Phase C booking engine's
 * {@code commerce.AvailabilityService} reads to generate real slots. Deliberately
 * separate from {@link CatalogService} (which stays a plain showcase CRUD file);
 * this one carries booking-specific business rules.
 */
@Service
public class StaffScheduleService {

    private final BusinessRepository businessRepository;
    private final TeamMemberRepository teamRepository;
    private final ServiceOfferingRepository serviceRepository;
    private final StaffServiceLinkRepository staffServiceRepository;
    private final StaffWeeklyScheduleRepository scheduleRepository;
    private final StaffTimeOffRepository timeOffRepository;

    public StaffScheduleService(BusinessRepository businessRepository,
                                TeamMemberRepository teamRepository,
                                ServiceOfferingRepository serviceRepository,
                                StaffServiceLinkRepository staffServiceRepository,
                                StaffWeeklyScheduleRepository scheduleRepository,
                                StaffTimeOffRepository timeOffRepository) {
        this.businessRepository = businessRepository;
        this.teamRepository = teamRepository;
        this.serviceRepository = serviceRepository;
        this.staffServiceRepository = staffServiceRepository;
        this.scheduleRepository = scheduleRepository;
        this.timeOffRepository = timeOffRepository;
    }

    /** Public — active staff qualified for a service. Empty means "not bookable" (req 1/13). */
    @Transactional(readOnly = true)
    public List<TeamMember> qualifiedActiveStaff(UUID businessId, UUID serviceId) {
        serviceRepository.findById(serviceId)
                .filter(s -> s.getBusinessId().equals(businessId))
                .orElseThrow(() -> new ResourceNotFoundException("Service not found"));
        Set<UUID> qualifiedIds = new HashSet<>(staffServiceRepository.findTeamMemberIdsByServiceId(serviceId));
        return teamRepository.findByBusinessIdOrderBySortOrderAsc(businessId).stream()
                .filter(TeamMember::isActive)
                .filter(t -> qualifiedIds.contains(t.getId()))
                .toList();
    }

    public record StaffScheduleResponse(List<StaffServiceAssignment> assignments, List<StaffWeeklySchedule> weeklySchedule,
                                        List<StaffTimeOff> timeOff) {
    }

    @Transactional(readOnly = true)
    public StaffScheduleResponse scheduleFor(UUID ownerUserId, UUID businessId, UUID staffId) {
        getOwnedOrThrow(ownerUserId, businessId);
        ownedStaff(staffId, businessId);
        List<StaffServiceAssignment> assignments = staffServiceRepository.findAssignmentsByTeamMemberId(staffId).stream()
                .map(l -> new StaffServiceAssignment(l.getId().getServiceId(), l.getDurationMinutes(), l.getBufferMinutes()))
                .toList();
        return new StaffScheduleResponse(
                assignments,
                scheduleRepository.findByTeamMemberId(staffId),
                timeOffRepository.findByTeamMemberIdOrderByStartDateAsc(staffId));
    }

    @Transactional
    public List<StaffWeeklySchedule> replaceSchedule(UUID ownerUserId, UUID businessId, UUID staffId, ReplaceScheduleRequest req) {
        getOwnedOrThrow(ownerUserId, businessId);
        ownedStaff(staffId, businessId);

        Set<DayOfWeek> seen = EnumSet.noneOf(DayOfWeek.class);
        for (WeeklyScheduleEntryRequest day : req.days()) {
            if (!seen.add(day.dayOfWeek())) {
                throw new BadRequestException("Each day of the week may only appear once.");
            }
            if (!day.endTime().isAfter(day.startTime())) {
                throw new BadRequestException(day.dayOfWeek() + ": end time must be after start time.");
            }
            if ((day.breakStart() == null) != (day.breakEnd() == null)) {
                throw new BadRequestException(day.dayOfWeek() + ": a break needs both a start and an end time.");
            }
            if (day.breakStart() != null && (!day.breakEnd().isAfter(day.breakStart())
                    || day.breakStart().isBefore(day.startTime()) || day.breakEnd().isAfter(day.endTime()))) {
                throw new BadRequestException(day.dayOfWeek() + ": break must fall within the working hours.");
            }
        }

        scheduleRepository.deleteByTeamMemberId(staffId);
        List<StaffWeeklySchedule> rows = req.days().stream()
                .map(d -> StaffWeeklySchedule.builder()
                        .teamMemberId(staffId)
                        .dayOfWeek(d.dayOfWeek())
                        .startTime(d.startTime())
                        .endTime(d.endTime())
                        .breakStart(d.breakStart())
                        .breakEnd(d.breakEnd())
                        .build())
                .toList();
        return scheduleRepository.saveAll(rows);
    }

    @Transactional
    public List<StaffServiceAssignment> replaceServices(UUID ownerUserId, UUID businessId, UUID staffId, StaffServiceAssignmentsRequest req) {
        getOwnedOrThrow(ownerUserId, businessId);
        ownedStaff(staffId, businessId);

        List<UUID> serviceIds = req.assignments().stream().map(StaffServiceAssignment::serviceId).toList();
        List<ServiceOffering> owned = serviceRepository.findAllById(serviceIds);
        if (owned.size() != new HashSet<>(serviceIds).size()
                || owned.stream().anyMatch(s -> !s.getBusinessId().equals(businessId))) {
            throw new BadRequestException("One or more services do not belong to this business.");
        }

        staffServiceRepository.deleteByTeamMemberId(staffId);
        staffServiceRepository.saveAll(req.assignments().stream()
                .map(a -> new StaffServiceLink(staffId, a.serviceId(), a.durationMinutes(), a.bufferMinutes()))
                .toList());
        return req.assignments();
    }

    @Transactional
    public StaffTimeOff addTimeOff(UUID ownerUserId, UUID businessId, UUID staffId, TimeOffRequest req) {
        getOwnedOrThrow(ownerUserId, businessId);
        ownedStaff(staffId, businessId);
        if (req.endDate().isBefore(req.startDate())) {
            throw new BadRequestException("End date can't be before the start date.");
        }
        return timeOffRepository.save(StaffTimeOff.builder()
                .teamMemberId(staffId)
                .startDate(req.startDate())
                .endDate(req.endDate())
                .reason(blankToNull(req.reason()))
                .build());
    }

    @Transactional
    public void removeTimeOff(UUID ownerUserId, UUID businessId, UUID staffId, UUID timeOffId) {
        getOwnedOrThrow(ownerUserId, businessId);
        ownedStaff(staffId, businessId);
        timeOffRepository.deleteByIdAndTeamMemberId(timeOffId, staffId);
    }

    // ================================================================
    // shared helpers
    // ================================================================
    private Business getOwnedOrThrow(UUID ownerUserId, UUID businessId) {
        Business business = businessRepository.findById(businessId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        if (!business.getOwnerUserId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this business listing");
        }
        return business;
    }

    private TeamMember ownedStaff(UUID staffId, UUID businessId) {
        TeamMember staff = teamRepository.findById(staffId)
                .orElseThrow(() -> new ResourceNotFoundException("Staff member not found"));
        if (!staff.getBusinessId().equals(businessId)) {
            throw new ResourceNotFoundException("Staff member not found");
        }
        return staff;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
