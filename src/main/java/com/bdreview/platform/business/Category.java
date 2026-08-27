package com.bdreview.platform.business;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/** Fixed taxonomy (electrician, restaurant, salon, ...) — deliberately not freeform. */
@Entity
@Table(name = "category")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Category {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /**
     * Drives category-specific modules (Phase 2). NOT NULL — backfilled from the
     * name in V19 and admin-editable thereafter; defaults to GENERAL for anything
     * new or unclassified.
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CategoryKind kind = CategoryKind.GENERAL;
}
