package com.bdreview.platform.review;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "review_photo")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ReviewPhoto {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Column(nullable = false, columnDefinition = "text")
    private String url;

    /** V63 photo moderation: PENDING / APPROVED / REJECTED — public reads only ever return APPROVED. */
    @Builder.Default
    @Column(name = "moderation_status", nullable = false, length = 12)
    private String moderationStatus = "APPROVED";

    public boolean isApproved() {
        return "APPROVED".equals(moderationStatus);
    }

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
