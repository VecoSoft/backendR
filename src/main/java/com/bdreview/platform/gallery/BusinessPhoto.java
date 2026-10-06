package com.bdreview.platform.gallery;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Spec §13. Only metadata/URL lives here — the upload itself goes straight from
 * the frontend to object storage via a backend-issued pre-signed URL, and photos
 * are served via CDN, never directly from the bucket.
 */
@Entity
@Table(name = "business_photo")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BusinessPhoto {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(nullable = false, columnDefinition = "text")
    private String url; // CDN URL

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

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
