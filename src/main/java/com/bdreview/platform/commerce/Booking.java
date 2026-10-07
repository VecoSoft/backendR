package com.bdreview.platform.commerce;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A customer's appointment request against a business (Phase C — currently
 * Salon & Beauty). Service and staff names are snapshotted at request time so
 * a later catalog edit never rewrites a past booking's record.
 */
@Entity
@Table(name = "business_booking")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Booking {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "customer_user_id", nullable = false)
    private UUID customerUserId;

    @Column(name = "booking_number", nullable = false, length = 20, updatable = false)
    private String bookingNumber;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingStatus status = BookingStatus.PENDING;

    @Column(name = "service_id")
    private UUID serviceId;

    @Column(name = "service_name_snapshot", nullable = false, length = 160)
    private String serviceNameSnapshot;

    @Column(name = "staff_id")
    private UUID staffId;

    @Column(name = "staff_name_snapshot", length = 160)
    private String staffNameSnapshot;

    @Column(name = "preferred_date", nullable = false)
    private LocalDate preferredDate;

    @Column(name = "preferred_time", nullable = false)
    private LocalTime preferredTime;

    /** Snapshotted from the service at booking time — a later catalog edit never rewrites a past booking's occupied window. */
    @Column(name = "duration_minutes_snapshot", nullable = false)
    private int durationMinutesSnapshot;

    @Column(name = "buffer_minutes_snapshot", nullable = false)
    private int bufferMinutesSnapshot;

    /** {@code preferredDate + preferredTime} .. {@code + duration + buffer} — the exact window the DB-level exclusion constraint guards. */
    @Column(name = "slot_start", nullable = false)
    private LocalDateTime slotStart;

    @Column(name = "slot_end", nullable = false)
    private LocalDateTime slotEnd;

    /** Whether this booking skipped owner approval (business had auto-confirm on) — display only. */
    @Builder.Default
    @Column(name = "auto_confirmed", nullable = false)
    private boolean autoConfirmed = false;

    /** Set when the owner taps "Start" on a CONFIRMED booking — not a status change, just live-queue metadata. */
    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "customer_name_snapshot", nullable = false, length = 120)
    private String customerNameSnapshot;

    @Column(name = "customer_phone_snapshot", length = 20)
    private String customerPhoneSnapshot;

    @Column(name = "customer_note", columnDefinition = "text")
    private String customerNote;

    @Column(name = "rejection_reason", length = 200)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
