package com.bdreview.platform.promo;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One Design Studio result (V58). {@code dataJson} holds only the owner's choices (headline,
 * subline, accent colour, photo, offer/menu refs, toggles) — never prices, ratings or offer
 * terms, which are read from their own tables every time the creative is rendered.
 */
@Entity
@Table(name = "promo_creative")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PromoCreative {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "template_key", nullable = false, length = 40)
    private String templateKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_json", nullable = false, columnDefinition = "jsonb")
    private String dataJson;

    @Column(name = "square_url", columnDefinition = "text")
    private String squareUrl;

    @Column(name = "story_url", columnDefinition = "text")
    private String storyUrl;

    @Column(name = "og_url", columnDefinition = "text")
    private String ogUrl;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
