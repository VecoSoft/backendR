package com.bdreview.platform.commerce;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Page<Booking> findByCustomerUserIdOrderByCreatedAtDesc(UUID customerUserId, Pageable pageable);

    Page<Booking> findByBusinessIdOrderByPreferredDateAscPreferredTimeAsc(UUID businessId, Pageable pageable);

    Page<Booking> findByBusinessIdAndStatusOrderByPreferredDateAscPreferredTimeAsc(UUID businessId, BookingStatus status, Pageable pageable);

    long countByBusinessIdAndStatus(UUID businessId, BookingStatus status);

    /**
     * Every PENDING/CONFIRMED booking for one staff member whose occupied window
     * overlaps {@code [start, end)} — the pool the availability engine checks. The
     * DB-level {@code no_staff_double_booking} exclusion constraint is the real
     * guarantee against a race; this query only powers slot generation and the
     * friendly pre-check before that constraint is ever reached.
     */
    @Query("select b from Booking b where b.staffId = :staffId and b.status in ('PENDING','CONFIRMED') " +
            "and b.slotStart < :end and b.slotEnd > :start")
    List<Booking> findOverlapping(@Param("staffId") UUID staffId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** One staff member's confirmed schedule for a day, in serving order — the pool the live queue/ETA is computed from. */
    List<Booking> findByStaffIdAndPreferredDateAndStatusOrderBySlotStartAsc(UUID staffId, LocalDate preferredDate, BookingStatus status);

    /** Next booking number from the DB sequence — used to build "B-1045". */
    @Query(value = "SELECT nextval('booking_number_seq')", nativeQuery = true)
    long nextBookingNumber();
}
