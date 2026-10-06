package com.bdreview.platform.accountcontrol;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Account-wide suspension or ban set by an ADMIN (V63) — separate from community-only
 * restrictions. While in force the user can't log in, refresh a session, or make any write
 * (see {@link AccountWriteGuard}).
 */
@Entity
@Table(name = "user_restriction")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class UserRestriction {

    public enum Type { SUSPEND, BAN }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Type type;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    /** Null for a BAN (permanent). */
    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "lifted_at")
    private Instant liftedAt;

    @Column(name = "lifted_by")
    private UUID liftedBy;

    @Column(name = "lift_reason", columnDefinition = "text")
    private String liftReason;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        if (this.startsAt == null) {
            this.startsAt = this.createdAt;
        }
    }

    public boolean isInEffect(Instant now) {
        return liftedAt == null && !startsAt.isAfter(now) && (endsAt == null || endsAt.isAfter(now));
    }

    /** ACTIVE / EXPIRED / LIFTED — for the admin history table. */
    public String state() {
        if (liftedAt != null) {
            return "LIFTED";
        }
        return isInEffect(Instant.now()) ? "ACTIVE" : "EXPIRED";
    }
}
