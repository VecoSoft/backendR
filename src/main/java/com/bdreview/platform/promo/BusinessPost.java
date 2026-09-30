package com.bdreview.platform.promo;

import com.bdreview.platform.promo.PromoEnums.BusinessPostStatus;
import com.bdreview.platform.promo.PromoEnums.BusinessPostType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Promotion sidecar of a community_post published as a business (V58) — 1:1 by post id. The
 * post's title/body/votes/comments/reports stay on community_post; this row carries only what
 * promotion needs (type, linked offer/menu item/creative, lifecycle, expiry).
 */
@Entity
@Table(name = "business_post")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BusinessPost {

    @Id
    @Column(name = "post_id")
    private UUID postId;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "author_user_id", nullable = false)
    private UUID authorUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BusinessPostType type;

    @Column(name = "creative_id")
    private UUID creativeId;

    @Column(name = "offer_id")
    private UUID offerId;

    @Column(name = "menu_item_id")
    private UUID menuItemId;

    @Column(name = "event_start")
    private Instant eventStart;

    @Column(name = "event_end")
    private Instant eventEnd;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BusinessPostStatus status = BusinessPostStatus.DRAFT;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Builder.Default
    @Column(name = "interested_count", nullable = false)
    private int interestedCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
