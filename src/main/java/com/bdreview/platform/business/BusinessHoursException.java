package com.bdreview.platform.business;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A date-range override on top of the recurring weekly hours (BusinessOperatingHours) —
 * a holiday closure or modified/special hours for specific dates. Always takes
 * precedence over the recurring weekly entry when its range covers "today".
 * closeTime <= openTime (when not closed) crosses midnight, same convention as
 * BusinessOperatingHours.
 */
@Entity
@Table(name = "business_hours_exception")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BusinessHoursException {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Builder.Default
    @Column(nullable = false)
    private boolean closed = true;

    @Column(name = "open_time")
    private LocalTime openTime;

    @Column(name = "close_time")
    private LocalTime closeTime;

    @Column(length = 200)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private java.time.Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = java.time.Instant.now();
    }
}
