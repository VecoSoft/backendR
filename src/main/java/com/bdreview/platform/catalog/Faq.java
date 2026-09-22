package com.bdreview.platform.catalog;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** One owner-answered common question — business-wide, not category-scoped. */
@Entity
@Table(name = "business_faq")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Faq {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(nullable = false, length = 300)
    private String question;

    @Column(nullable = false, columnDefinition = "text")
    private String answer;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
