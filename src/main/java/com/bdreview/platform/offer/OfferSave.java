package com.bdreview.platform.offer;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Mirrors bookmark.Bookmark's shape — a separate small table rather than widening Bookmark, which is business-only and already shipped. */
@Entity
@Table(name = "offer_save", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "offer_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class OfferSave {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "offer_id", nullable = false)
    private UUID offerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
