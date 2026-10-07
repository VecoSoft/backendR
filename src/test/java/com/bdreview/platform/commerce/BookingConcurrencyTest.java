package com.bdreview.platform.commerce;

import com.bdreview.platform.business.Area;
import com.bdreview.platform.business.AreaRepository;
import com.bdreview.platform.business.Business;
import com.bdreview.platform.business.BusinessRepository;
import com.bdreview.platform.business.Category;
import com.bdreview.platform.business.CategoryKind;
import com.bdreview.platform.business.CategoryRepository;
import com.bdreview.platform.business.City;
import com.bdreview.platform.business.CityRepository;
import com.bdreview.platform.business.PriceTier;
import com.bdreview.platform.auth.User;
import com.bdreview.platform.auth.UserRepository;
import com.bdreview.platform.auth.UserRole;
import com.bdreview.platform.catalog.ServiceOffering;
import com.bdreview.platform.catalog.ServiceOfferingRepository;
import com.bdreview.platform.catalog.ServiceSection;
import com.bdreview.platform.catalog.StaffServiceLink;
import com.bdreview.platform.catalog.StaffServiceLinkRepository;
import com.bdreview.platform.catalog.StaffWeeklySchedule;
import com.bdreview.platform.catalog.StaffWeeklyScheduleRepository;
import com.bdreview.platform.catalog.TeamMember;
import com.bdreview.platform.catalog.TeamMemberRepository;
import com.bdreview.platform.commerce.CommerceRequests.PlaceBookingRequest;
import com.bdreview.platform.commerce.CommerceResponses.BookingResponse;
import com.bdreview.platform.common.ConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the DB-level guarantee behind req 5 ("two customers must NEVER be
 * able to successfully book the same staff/time interval") — the
 * {@code no_staff_double_booking} exclusion constraint added in V27, not just
 * an application-level check. Runs against the real local dev Postgres (no
 * Testcontainers in this repo — see the Stage 1 plan's Context §4) and cleans
 * up every row it creates in {@link #cleanUp()}.
 */
@SpringBootTest
class BookingConcurrencyTest {

    @Autowired CategoryRepository categoryRepository;
    @Autowired CityRepository cityRepository;
    @Autowired AreaRepository areaRepository;
    @Autowired BusinessRepository businessRepository;
    @Autowired BusinessCommerceSettingsRepository settingsRepository;
    @Autowired ServiceOfferingRepository serviceRepository;
    @Autowired TeamMemberRepository teamRepository;
    @Autowired StaffServiceLinkRepository staffServiceRepository;
    @Autowired StaffWeeklyScheduleRepository scheduleRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired BookingStatusEventRepository eventRepository;
    @Autowired UserRepository userRepository;
    @Autowired com.bdreview.platform.notification.NotificationRepository notificationRepository;
    @Autowired BookingService bookingService;

    UUID businessId;
    UUID ownerUserId;
    UUID serviceId;
    UUID staffId;
    UUID customerUserId;
    LocalDate targetDate;
    LocalTime targetTime = LocalTime.of(10, 0);

    @BeforeEach
    void setUp() {
        Category salon = categoryRepository.findAll().stream()
                .filter(c -> c.getKind() == CategoryKind.SALON)
                .findFirst().orElseThrow(() -> new IllegalStateException("No SALON category seeded"));
        City city = cityRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No city seeded"));
        Area area = areaRepository.findByCityId(city.getId()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No area seeded"));

        // Randomized per run — avoids a unique-constraint collision with a leftover
        // row from any previous run that didn't get a chance to clean up.
        String nanos = String.valueOf(System.nanoTime());
        String runSuffix = nanos.substring(nanos.length() - 7);

        User owner = userRepository.save(User.builder()
                .phoneNumber("+88016" + runSuffix)
                .email(java.util.UUID.randomUUID() + "@it.jachai.test").emailVerifiedAt(java.time.Instant.now())
                .role(UserRole.BUSINESS_OWNER)
                .build());
        ownerUserId = owner.getId();

        Business business = businessRepository.save(Business.builder()
                .ownerUserId(ownerUserId)
                .name("IT Concurrency Salon")
                .slug("it-concurrency-salon-" + UUID.randomUUID())
                .category(salon)
                .city(city)
                .area(area)
                .contactNumber("+88017" + runSuffix)
                .location(GeoPoints.of(23.75, 90.38))
                .priceTier(PriceTier.MODERATE)
                .build());
        businessId = business.getId();

        settingsRepository.save(BusinessCommerceSettings.builder()
                .businessId(businessId)
                .mode(CommerceMode.BOOKING)
                .bookingEnabled(true)
                .build());

        ServiceOffering service = serviceRepository.save(ServiceOffering.builder()
                .businessId(businessId)
                .section(ServiceSection.OFFERING)
                .name("IT Haircut")
                .durationMinutes(30)
                .bufferMinutes(0)
                .build());
        serviceId = service.getId();

        TeamMember staff = teamRepository.save(TeamMember.builder()
                .businessId(businessId)
                .name("IT Staff")
                .active(true)
                .build());
        staffId = staff.getId();

        staffServiceRepository.save(new StaffServiceLink(staffId, serviceId));

        User customer = userRepository.save(User.builder()
                .phoneNumber("+88018" + runSuffix)
                .email(java.util.UUID.randomUUID() + "@it.jachai.test").emailVerifiedAt(java.time.Instant.now())
                .role(UserRole.CONSUMER)
                .build());
        customerUserId = customer.getId();

        // Pick the next date that actually falls on a day we grant a wide working window.
        targetDate = LocalDate.now().plusDays(7);
        scheduleRepository.save(StaffWeeklySchedule.builder()
                .teamMemberId(staffId)
                .dayOfWeek(targetDate.getDayOfWeek())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .build());
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        // Give the @Async NEW_BOOKING notification dispatch a moment to land before
        // we delete the rows it references — it fires off the request thread, so it
        // may otherwise still be in flight when this method starts.
        Thread.sleep(300);
        // Standard CrudRepository methods only (deleteAll/deleteById) — these are
        // transactional out of the box via SimpleJpaRepository, unlike the custom
        // @Modifying bulk-delete queries the production code uses, which require
        // an already-open transaction that a plain @AfterEach method doesn't have.
        bookingRepository.findAll().stream()
                .filter(b -> b.getBusinessId().equals(businessId))
                .forEach(b -> {
                    eventRepository.deleteAll(eventRepository.findByBookingIdOrderByCreatedAtAsc(b.getId()));
                    bookingRepository.deleteById(b.getId());
                });
        staffServiceRepository.deleteById(new StaffServiceLink(staffId, serviceId).getId());
        scheduleRepository.deleteAll(scheduleRepository.findByTeamMemberId(staffId));
        teamRepository.deleteById(staffId);
        serviceRepository.deleteById(serviceId);
        settingsRepository.deleteById(businessId);
        businessRepository.deleteById(businessId);
        // The @Async NEW_BOOKING notification to the owner may or may not have landed
        // by now — clear it either way before the FK-referenced user rows go.
        notificationRepository.deleteAll(notificationRepository
                .findByRecipientUserIdAndChannelOrderByCreatedAtDesc(ownerUserId, com.bdreview.platform.notification.NotificationChannel.IN_APP, org.springframework.data.domain.Pageable.unpaged())
                .getContent());
        userRepository.deleteById(customerUserId);
        userRepository.deleteById(ownerUserId);
    }

    @Test
    void onlyOneOfTwoSimultaneousBookingsForTheSameStaffAndSlotSucceeds() throws Exception {
        PlaceBookingRequest req = new PlaceBookingRequest(serviceId, staffId, targetDate, targetTime,
                "Race Customer", null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Callable<Object> attempt = () -> {
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            try {
                return bookingService.placeBooking(customerUserId, businessId, req);
            } catch (Exception e) {
                return e;
            }
        };

        Future<Object> f1 = pool.submit(attempt);
        Future<Object> f2 = pool.submit(attempt);
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();

        Object r1 = f1.get(10, TimeUnit.SECONDS);
        Object r2 = f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        List<Object> results = List.of(r1, r2);
        long successes = results.stream().filter(r -> r instanceof BookingResponse).count();
        long conflicts = results.stream().filter(r -> r instanceof ConflictException).count();

        assertThat(successes).as("exactly one booking should win the race").isEqualTo(1);
        assertThat(conflicts).as("the other should be told the slot was just taken").isEqualTo(1);

        List<Booking> saved = bookingRepository.findOverlapping(staffId,
                targetDate.atTime(targetTime), targetDate.atTime(targetTime).plusMinutes(30));
        assertThat(saved).hasSize(1);
    }
}
