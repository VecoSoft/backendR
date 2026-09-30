package com.bdreview.platform.community.moderation;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A moderator-imposed community restriction (V56).
 * <ul>
 *   <li>WARN — a recorded warning; blocks nothing, shown on /account.</li>
 *   <li>MUTE — read-only: every community write (post, comment, vote, report, follow, poll vote) → 403.</li>
 *   <li>SUSPEND — community access suspended: same write block, plus profile changes (username/avatar).</li>
 *   <li>BAN — permanent SUSPEND ({@code endsAt} is null).</li>
 * </ul>
 * {@code endsAt} null = permanent. Status moves ACTIVE → EXPIRED via
 * {@link CommunityRestrictionExpiryJob}, or ACTIVE → LIFTED by a moderator; enforcement also
 * checks {@code endsAt} directly so an expired row never blocks even before the job runs.
 */
@Entity
@Table(name = "community_restriction")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CommunityRestriction {

    public enum Type { WARN, MUTE, SUSPEND, BAN }

    public enum Status { ACTIVE, EXPIRED, LIFTED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "lifted_by")
    private UUID liftedBy;

    @Column(name = "lifted_at")
    private Instant liftedAt;

    @Column(name = "lift_reason", columnDefinition = "text")
    private String liftReason;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        if (this.startsAt == null) {
            this.startsAt = this.createdAt;
        }
    }

    @Transient
    public boolean isInEffect(Instant now) {
        return status == Status.ACTIVE && !startsAt.isAfter(now) && (endsAt == null || endsAt.isAfter(now));
    }

    /** MUTE/SUSPEND/BAN block community writes; WARN never does. */
    @Transient
    public boolean blocksWrites() {
        return type != Type.WARN;
    }
}
