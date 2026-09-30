package com.bdreview.platform.promo;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Admin suspension of a business's promotion rights (V58). Active while not lifted and not past endsAt. */
@Entity
@Table(name = "promotion_restriction")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PromotionRestriction {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "lifted_at")
    private Instant liftedAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isActiveAt(Instant now) {
        return liftedAt == null && (endsAt == null || endsAt.isAfter(now));
    }
}
