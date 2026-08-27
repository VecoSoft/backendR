package com.bdreview.platform.catalog;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A person shown on a listing's team showcase (Phase 2) — doctors (CLINIC),
 * staff (SALON), trainers (GYM). Reused across those kinds; the public label
 * ("Doctors" / "Staff" / "Trainers") is chosen on the frontend from the
 * category kind.
 */
@Entity
@Table(name = "business_team_member")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TeamMember {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(nullable = false, length = 160)
    private String name;

    /** "Cardiologist", "Senior Stylist", "Head Trainer". */
    @Column(length = 160)
    private String role;

    @Column(columnDefinition = "text")
    private String bio;

    @Column(name = "photo_url", columnDefinition = "text")
    private String photoUrl;

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
