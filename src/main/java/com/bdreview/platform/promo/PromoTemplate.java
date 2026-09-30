package com.bdreview.platform.promo;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A Design Studio template (V58). The layout itself is code (frontend/src/components/promo/
 * templates.tsx, keyed by {@link #key}); this row is the admin-controlled part — enabled, order,
 * which post types it fits, and its config (slots, colour rule).
 */
@Entity
@Table(name = "promo_template")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class PromoTemplate {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 40)
    private String key;

    @Column(nullable = false, length = 80)
    private String name;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "supported_types", nullable = false, columnDefinition = "text[]")
    private List<String> supportedTypes;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private List<String> formats;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
    private String configJson;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
