package com.bdreview.platform.business;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Groups multiple independent {@link Business} rows into one chain (e.g. "KFC").
 * Deliberately minimal — name/slug/logo only. Rating and branch count are never
 * stored here; they're computed on read from the linked businesses (see
 * BusinessRepository#aggregatesFor) so this table never needs to be kept in sync
 * with the per-business rating-aggregate writes.
 */
@Entity
@Table(name = "brand")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Brand {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String name;

    /** Generated at creation, immutable thereafter. On collision, a random suffix is appended. */
    @Column(nullable = false, length = 280)
    private String slug;

    @Column(name = "logo_url", columnDefinition = "text")
    private String logoUrl;

    @Column(name = "deleted_at")
    private Instant deletedAt;

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

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
