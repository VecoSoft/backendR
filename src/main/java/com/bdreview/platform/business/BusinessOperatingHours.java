package com.bdreview.platform.business;

import jakarta.persistence.*;
import lombok.*;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One day's structured hours for a business. {@code closeTime <= openTime}
 * (when not closed) means the window crosses midnight — there is no separate
 * "crosses midnight" flag, it's inferred from the times, same convention as
 * the DB CHECK constraint in V38__business_operating_hours.sql.
 */
@Entity
@Table(name = "business_operating_hours")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BusinessOperatingHours {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 10)
    private DayOfWeek dayOfWeek;

    @Column(nullable = false)
    private boolean closed;

    @Column(name = "open_time")
    private LocalTime openTime;

    @Column(name = "close_time")
    private LocalTime closeTime;
}
