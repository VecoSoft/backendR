package com.bdreview.platform.community;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Community discussion topic (V56 — formerly a hardcoded enum + CHECK constraint). Admin-managed
 * from the admin panel's Community settings: CRUD, reorder, icon/color, enable/disable and one
 * default. {@code code} is what posts store and what the API has always sent ("FOOD", ...).
 * A disabled topic stays valid for existing posts (their badge still renders) but can't be
 * chosen for a new post.
 */
@Entity
@Table(name = "community_topic")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CommunityTopic {

    @Id
    @Column(length = 20)
    private String code;

    @Column(nullable = false, length = 60)
    private String label;

    @Column(name = "label_bn", length = 60)
    private String labelBn;

    /** lucide-react icon name used by the Next.js app (e.g. "utensils"). */
    @Column(length = 40)
    private String icon;

    @Column(length = 20)
    private String color;

    @Builder.Default
    @Column(nullable = false)
    private int position = 0;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private boolean defaultTopic = false;

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
}
