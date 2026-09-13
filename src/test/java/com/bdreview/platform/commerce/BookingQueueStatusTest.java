package com.bdreview.platform.commerce;

import com.bdreview.platform.business.Business;
import com.bdreview.platform.catalog.ServiceOfferingRepository;
import com.bdreview.platform.catalog.StaffServiceLinkRepository;
import com.bdreview.platform.catalog.TeamMemberRepository;
import com.bdreview.platform.commerce.CommerceResponses.QueueStatusResponse;
import com.bdreview.platform.common.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Pure-JUnit coverage of the live queue-position/ETA math and the "one active
 * job per staff member" guard on {@code start} — no Spring context, no DB.
 * Each ahead booking contributes its own snapshotted duration+buffer, except
 * the one currently in progress (if any), which contributes only its
 * remaining time — see {@code BookingService#queueStatus}.
 */
@ExtendWith(MockitoExtension.class)
class BookingQueueStatusTest {

    @Mock BookingRepository bookingRepo;
    @Mock BookingStatusEventRepository eventRepo;
    @Mock ServiceOfferingRepository serviceRepo;
    @Mock TeamMemberRepository teamRepo;
    @Mock StaffServiceLinkRepository staffServiceRepo;
    @Mock AvailabilityService availability;
    @Mock CommerceSettingsService settingsService;
    @Mock CommerceGuard guard;
    @Mock CommerceNotifier notifier;

    BookingService bookingService;

    UUID businessId;
    UUID ownerUserId;
    UUID staffId;
    UUID customerUserId;
    LocalDate today;

    @BeforeEach
    void setUp() {
        bookingService = new BookingService(bookingRepo, eventRepo, serviceRepo, teamRepo, staffServiceRepo,
                availability, settingsService, guard, notifier, null);
        businessId = UUID.randomUUID();
        ownerUserId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        customerUserId = UUID.randomUUID();
        today = LocalDate.now();

        Business business = Business.builder().id(businessId).ownerUserId(ownerUserId).build();
        lenient().when(guard.getLiveOrThrow(businessId)).thenReturn(business);
        lenient().when(guard.getOwnedOrThrow(ownerUserId, businessId)).thenReturn(business);
    }

    private Booking booking(String number, LocalTime time, int duration, int buffer, Instant startedAt) {
        LocalDateTime slotStart = LocalDateTime.of(today, time);
        return Booking.builder()
                .id(UUID.randomUUID())
                .businessId(businessId)
                .customerUserId(customerUserId)
                .bookingNumber(number)
                .status(BookingStatus.CONFIRMED)
                .staffId(staffId)
                .staffNameSnapshot("Rina")
                .serviceNameSnapshot("Haircut")
                .preferredDate(today)
                .preferredTime(time)
                .durationMinutesSnapshot(duration)
                .bufferMinutesSnapshot(buffer)
                .slotStart(slotStart)
                .slotEnd(slotStart.plusMinutes(duration + buffer))
                .startedAt(startedAt)
                .customerNameSnapshot("Cust " + number)
                .customerPhoneSnapshot("01700000000")
                .build();
    }

    @Test
    void nothingStartedYetSumsFullDurationsAhead() {
        Booking first = booking("B-1", LocalTime.of(10, 0), 30, 0, null);
        Booking second = booking("B-2", LocalTime.of(10, 30), 30, 0, null);
        Booking target = booking("B-3", LocalTime.of(11, 0), 30, 0, null);

        when(bookingRepo.findById(target.getId())).thenReturn(Optional.of(target));
        when(bookingRepo.findByStaffIdAndPreferredDateAndStatusOrderBySlotStartAsc(staffId, today, BookingStatus.CONFIRMED))
                .thenReturn(List.of(first, second, target));

        QueueStatusResponse res = bookingService.queueStatus(customerUserId, target.getId());

        assertThat(res.applicable()).isTrue();
        assertThat(res.position()).isEqualTo(2);
        assertThat(res.estimatedWaitMinutes()).isEqualTo(60);
        assertThat(res.currentlyServingService()).isNull();
    }

    @Test
    void inProgressJobContributesOnlyItsRemainingTime() {
        Instant startedAt = Instant.now().minusSeconds(20 * 60); // 20 minutes into a 30-minute job
        Booking first = booking("B-1", LocalTime.of(10, 0), 30, 0, startedAt);
        Booking second = booking("B-2", LocalTime.of(10, 30), 30, 0, null);
        Booking target = booking("B-3", LocalTime.of(11, 0), 30, 0, null);

        when(bookingRepo.findById(target.getId())).thenReturn(Optional.of(target));
        when(bookingRepo.findByStaffIdAndPreferredDateAndStatusOrderBySlotStartAsc(staffId, today, BookingStatus.CONFIRMED))
                .thenReturn(List.of(first, second, target));

        QueueStatusResponse res = bookingService.queueStatus(customerUserId, target.getId());

        assertThat(res.position()).isEqualTo(2);
        assertThat(res.estimatedWaitMinutes()).isEqualTo(40); // ~10 remaining + 30 full
        assertThat(res.currentlyServingService()).isEqualTo("Haircut");
    }

    @Test
    void anOverrunningJobNeverContributesNegativeTime() {
        Instant startedAt = Instant.now().minusSeconds(90 * 60); // way past its 30-minute duration
        Booking first = booking("B-1", LocalTime.of(10, 0), 30, 0, startedAt);
        Booking target = booking("B-2", LocalTime.of(10, 30), 30, 0, null);

        when(bookingRepo.findById(target.getId())).thenReturn(Optional.of(target));
        when(bookingRepo.findByStaffIdAndPreferredDateAndStatusOrderBySlotStartAsc(staffId, today, BookingStatus.CONFIRMED))
                .thenReturn(List.of(first, target));

        QueueStatusResponse res = bookingService.queueStatus(customerUserId, target.getId());

        assertThat(res.estimatedWaitMinutes()).isEqualTo(0);
    }

    @Test
    void notApplicableForAFutureDate() {
        Booking future = booking("B-4", LocalTime.of(10, 0), 30, 0, null);
        future.setPreferredDate(today.plusDays(1));
        when(bookingRepo.findById(future.getId())).thenReturn(Optional.of(future));

        QueueStatusResponse res = bookingService.queueStatus(customerUserId, future.getId());

        assertThat(res.applicable()).isFalse();
        assertThat(res.position()).isNull();
    }

    @Test
    void ownerCannotStartWhileAnotherIsAlreadyInProgress() {
        Booking running = booking("B-1", LocalTime.of(10, 0), 30, 0, Instant.now());
        Booking toStart = booking("B-2", LocalTime.of(10, 30), 30, 0, null);

        when(bookingRepo.findById(toStart.getId())).thenReturn(Optional.of(toStart));
        when(bookingRepo.findByStaffIdAndPreferredDateAndStatusOrderBySlotStartAsc(staffId, today, BookingStatus.CONFIRMED))
                .thenReturn(List.of(running, toStart));

        assertThatThrownBy(() -> bookingService.start(ownerUserId, toStart.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already serving");
    }
}
