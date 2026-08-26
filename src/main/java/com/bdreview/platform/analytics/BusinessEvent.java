package com.bdreview.platform.analytics;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One tracked interaction on a public business page (Phase 3). Deliberately
 * minimal: business + type + timestamp, plus an opaque {@code sessionId} used
 * ONLY to de-duplicate PROFILE_VIEW. No IP, no user agent, no user id.
 */
@Entity
@Table(name = "business_event")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BusinessEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private BusinessEventType eventType;

    /** Client-generated random string (sessionStorage). Nullable, non-identifying. */
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
