package com.bdreview.platform.commerce;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BookingStatusEventRepository extends JpaRepository<BookingStatusEvent, UUID> {

    List<BookingStatusEvent> findByBookingIdOrderByCreatedAtAsc(UUID bookingId);
}
