package com.bdreview.platform.commerce;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Append-only audit row — one per booking status change. */
@Entity
@Table(name = "booking_status_event")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BookingStatusEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 16)
    private BookingStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 16)
    private BookingStatus toStatus;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(length = 200)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
