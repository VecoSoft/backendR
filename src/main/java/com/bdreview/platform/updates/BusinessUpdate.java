package com.bdreview.platform.updates;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A lightweight owner announcement on a listing (Phase 3) — text, an optional
 * image, and a published flag. Not a social object: no reactions, comments,
 * or cross-business feed.
 */
@Entity
@Table(name = "business_update")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BusinessUpdate {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "image_url", columnDefinition = "text")
    private String imageUrl;

    @Builder.Default
    @Column(nullable = false)
    private boolean published = true;

    /** Set whenever {@link #published} flips to true; the public display date. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.published && this.publishedAt == null) {
            this.publishedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
