package com.bdreview.platform.commerce;

import com.bdreview.platform.catalog.ServiceOffering;
import com.bdreview.platform.catalog.ServiceOfferingRepository;
import com.bdreview.platform.catalog.ServiceSection;
import com.bdreview.platform.catalog.StaffServiceLink;
import com.bdreview.platform.catalog.StaffServiceLinkRepository;
import com.bdreview.platform.catalog.StaffTimeOffRepository;
import com.bdreview.platform.catalog.StaffWeeklySchedule;
import com.bdreview.platform.catalog.StaffWeeklyScheduleRepository;
import com.bdreview.platform.catalog.TeamMember;
import com.bdreview.platform.catalog.TeamMemberRepository;
import com.bdreview.platform.commerce.AvailabilityService.AvailabilityResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Pure-JUnit coverage of the slot-generation math (no Spring context, no DB) —
 * schedule/break/time-off/duration+buffer, and "any available staff" picking
 * whichever qualified staff member is actually free. The DB-level
 * {@code no_staff_double_booking} exclusion constraint (see V27, exercised by
 * {@link BookingConcurrencyTest}) is the real race-proof guarantee; this class
 * only proves the slot math that decides what to *offer* is correct.
 */
@ExtendWith(MockitoExtension.class)
class AvailabilityServiceTest {

    @Mock ServiceOfferingRepository serviceRepo;
    @Mock TeamMemberRepository teamRepo;
    @Mock StaffServiceLinkRepository staffServiceRepo;
    @Mock StaffWeeklyScheduleRepository scheduleRepo;
    @Mock StaffTimeOffRepository timeOffRepo;
    @Mock BookingRepository bookingRepo;

    AvailabilityService availability;

    UUID businessId;
    UUID serviceId;
    UUID staffAId;
    UUID staffBId;
    ServiceOffering service;
    LocalDate monday; // a known Monday

    @BeforeEach
    void setUp() {
        availability = new AvailabilityService(serviceRepo, teamRepo, staffServiceRepo, scheduleRepo, timeOffRepo, bookingRepo);

        businessId = UUID.randomUUID();
        serviceId = UUID.randomUUID();
        staffAId = UUID.randomUUID();
        staffBId = UUID.randomUUID();
        service = ServiceOffering.builder()
                .id(serviceId).businessId(businessId).section(ServiceSection.OFFERING)
                .name("Haircut").durationMinutes(30).bufferMinutes(0).build();
        monday = LocalDate.of(2026, 9, 14); // a Monday
        assertThat(monday.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);

        // lenient: not every test reaches these (e.g. the time-off short-circuit, or the plain default-fallback test)
        lenient().when(serviceRepo.findById(serviceId)).thenReturn(Optional.of(service));
        lenient().when(bookingRepo.findOverlapping(any(), any(), any())).thenReturn(List.of());
    }

    private TeamMember staff(UUID id) {
        return TeamMember.builder().id(id).businessId(businessId).name("Staff " + id).active(true).build();
    }

    private StaffWeeklySchedule schedule(UUID staffId, LocalTime start, LocalTime end, LocalTime breakStart, LocalTime breakEnd) {
        return StaffWeeklySchedule.builder()
                .teamMemberId(staffId).dayOfWeek(DayOfWeek.MONDAY)
                .startTime(start).endTime(end).breakStart(breakStart).breakEnd(breakEnd)
                .build();
    }

    @Test
    void slotsStepByDurationAcrossTheWholeWorkingWindow() {
        when(teamRepo.findById(staffAId)).thenReturn(Optional.of(staff(staffAId)));
        when(staffServiceRepo.findServiceIdsByTeamMemberId(staffAId)).thenReturn(List.of(serviceId));
        when(scheduleRepo.findByTeamMemberIdAndDayOfWeek(staffAId, DayOfWeek.MONDAY))
                .thenReturn(Optional.of(schedule(staffAId, LocalTime.of(9, 0), LocalTime.of(11, 0), null, null)));
        when(timeOffRepo.existsOnDate(staffAId, monday)).thenReturn(false);

        AvailabilityResponse res = availability.availability(businessId, serviceId, staffAId, monday);

        assertThat(res.slots()).extracting(s -> s.time().toString())
                .containsExactly("09:00", "09:30", "10:00", "10:30");
        assertThat(res.slots()).allMatch(AvailabilityService.AvailabilitySlot::available);
    }

    @Test
    void slotCrossingTheBreakIsExcludedButSlotsAroundItRemain() {
        service.setDurationMinutes(60);
        when(teamRepo.findById(staffAId)).thenReturn(Optional.of(staff(staffAId)));
        when(staffServiceRepo.findServiceIdsByTeamMemberId(staffAId)).thenReturn(List.of(serviceId));
        when(scheduleRepo.findByTeamMemberIdAndDayOfWeek(staffAId, DayOfWeek.MONDAY)).thenReturn(Optional.of(
                schedule(staffAId, LocalTime.of(9, 0), LocalTime.of(17, 0), LocalTime.of(13, 0), LocalTime.of(14, 0))));
        when(timeOffRepo.existsOnDate(staffAId, monday)).thenReturn(false);

        AvailabilityResponse res = availability.availability(businessId, serviceId, staffAId, monday);

        assertThat(res.slots()).extracting(s -> s.time().toString())
                .containsExactly("09:00", "10:00", "11:00", "12:00", "14:00", "15:00", "16:00");
    }

    @Test
    void noSlotsOnATimeOffDate() {
        when(teamRepo.findById(staffAId)).thenReturn(Optional.of(staff(staffAId)));
        when(staffServiceRepo.findServiceIdsByTeamMemberId(staffAId)).thenReturn(List.of(serviceId));
        when(timeOffRepo.existsOnDate(staffAId, monday)).thenReturn(true);

        AvailabilityResponse res = availability.availability(businessId, serviceId, staffAId, monday);

        assertThat(res.slots()).isEmpty();
    }

    @Test
    void aSlotAlreadyBookedForThatStaffMemberIsMarkedUnavailable() {
        when(teamRepo.findById(staffAId)).thenReturn(Optional.of(staff(staffAId)));
        when(staffServiceRepo.findServiceIdsByTeamMemberId(staffAId)).thenReturn(List.of(serviceId));
        when(scheduleRepo.findByTeamMemberIdAndDayOfWeek(staffAId, DayOfWeek.MONDAY))
                .thenReturn(Optional.of(schedule(staffAId, LocalTime.of(9, 0), LocalTime.of(10, 0), null, null)));
        when(timeOffRepo.existsOnDate(staffAId, monday)).thenReturn(false);

        LocalDateTime bookedStart = LocalDateTime.of(monday, LocalTime.of(9, 30));
        LocalDateTime bookedEnd = bookedStart.plusMinutes(30);
        when(bookingRepo.findOverlapping(eq(staffAId), eq(bookedStart), eq(bookedEnd)))
                .thenReturn(List.of(new Booking()));

        AvailabilityResponse res = availability.availability(businessId, serviceId, staffAId, monday);

        assertThat(res.slots()).hasSize(2);
        assertThat(res.slots().get(0).available()).isTrue(); // 09:00
        assertThat(res.slots().get(1).available()).isFalse(); // 09:30 — taken
    }

    @Test
    void anyAvailableStaffIsAvailableWhenAtLeastOneQualifiedStaffIsFree() {
        when(staffServiceRepo.findTeamMemberIdsByServiceId(serviceId)).thenReturn(List.of(staffAId, staffBId));
        when(teamRepo.findByBusinessIdOrderBySortOrderAsc(businessId))
                .thenReturn(List.of(staff(staffAId), staff(staffBId)));
        when(scheduleRepo.findByTeamMemberIdAndDayOfWeek(staffAId, DayOfWeek.MONDAY))
                .thenReturn(Optional.of(schedule(staffAId, LocalTime.of(9, 0), LocalTime.of(9, 30), null, null)));
        when(scheduleRepo.findByTeamMemberIdAndDayOfWeek(staffBId, DayOfWeek.MONDAY))
                .thenReturn(Optional.of(schedule(staffBId, LocalTime.of(9, 0), LocalTime.of(9, 30), null, null)));
        when(timeOffRepo.existsOnDate(any(), eq(monday))).thenReturn(false);

        LocalDateTime start = LocalDateTime.of(monday, LocalTime.of(9, 0));
        LocalDateTime end = start.plusMinutes(30);
        // staff A is busy, staff B is free
        when(bookingRepo.findOverlapping(staffAId, start, end)).thenReturn(List.of(new Booking()));
        when(bookingRepo.findOverlapping(staffBId, start, end)).thenReturn(List.of());

        AvailabilityResponse res = availability.availability(businessId, serviceId, null, monday);

        assertThat(res.slots()).hasSize(1);
        assertThat(res.slots().get(0).available()).isTrue();
    }

    @Test
    void staffSpecificDurationOverrideChangesSlotSpacing() {
        when(teamRepo.findById(staffAId)).thenReturn(Optional.of(staff(staffAId)));
        when(staffServiceRepo.findServiceIdsByTeamMemberId(staffAId)).thenReturn(List.of(serviceId));
        when(scheduleRepo.findByTeamMemberIdAndDayOfWeek(staffAId, DayOfWeek.MONDAY))
                .thenReturn(Optional.of(schedule(staffAId, LocalTime.of(9, 0), LocalTime.of(11, 0), null, null)));
        when(timeOffRepo.existsOnDate(staffAId, monday)).thenReturn(false);
        // the service's own default is 30 min; this staff member takes 60 min for it
        when(staffServiceRepo.findById(new StaffServiceLink.Id(staffAId, serviceId)))
                .thenReturn(Optional.of(new StaffServiceLink(staffAId, serviceId, 60, null)));

        AvailabilityResponse res = availability.availability(businessId, serviceId, staffAId, monday);

        assertThat(res.durationMinutes()).isEqualTo(60);
        assertThat(res.slots()).extracting(s -> s.time().toString()).containsExactly("09:00", "10:00");
    }

    @Test
    void resolveDurationAndBufferFallBackToDefaultsWhenUnset() {
        ServiceOffering bare = ServiceOffering.builder().id(UUID.randomUUID()).businessId(businessId)
                .section(ServiceSection.OFFERING).name("Facial").build();
        assertThat(AvailabilityService.resolveDuration(bare)).isEqualTo(AvailabilityService.DEFAULT_DURATION_MIN);
        assertThat(AvailabilityService.resolveBuffer(bare)).isZero();
    }
}
