package com.bdreview.platform.review;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Spec §4/§14. Edit/delete window is a fixed 72 hours from submission.
 * visibilityStatus/suspicionScore are written by the fake-review pipeline
 * (see the `fakereview` package); rating-aggregate updates on the owning
 * Business go through the event-driven fast path and must not wait on that
 * ML analysis.
 */
@Entity
@Table(name = "review")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Review {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private short rating; // 1-5

    @Column(columnDefinition = "text")
    private String content;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "visibility_status", nullable = false, length = 20)
    private VisibilityStatus visibilityStatus = VisibilityStatus.RECOMMENDED;

    @Builder.Default
    @Column(name = "suspicion_score", nullable = false)
    private short suspicionScore = 0;

    @Builder.Default
    @Column(name = "useful_count", nullable = false)
    private int usefulCount = 0;

    @Builder.Default
    @Column(name = "funny_count", nullable = false)
    private int funnyCount = 0;

    @Builder.Default
    @Column(name = "cool_count", nullable = false)
    private int coolCount = 0;

    /** Public owner reply (Google/Yelp-style "Response from the owner") — distinct from the private message thread. */
    @Column(name = "owner_reply", columnDefinition = "text")
    private String ownerReply;

    @Column(name = "owner_replied_at")
    private Instant ownerRepliedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    private long version;

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

    /**
     * Edit/delete window (spec §4: 72h; admin-configurable since V65 — see ReviewPolicyHolder),
     * computed from createdAt — never stored redundantly.
     */
    public boolean isWithinEditWindow() {
        return createdAt != null
                && Instant.now().isBefore(createdAt.plus(ReviewPolicyHolder.editWindowHours(), ChronoUnit.HOURS));
    }
}
