package com.bdreview.platform.community;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** One photo attachment on a CommunityPost — a post may attach several (see V43). */
@Entity
@Table(name = "community_post_photo")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CommunityPostPhoto {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    @Column(nullable = false, columnDefinition = "text")
    private String url;

    @Builder.Default
    @Column(nullable = false)
    private short position = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Moderator removed just this one image (V56) — the row stays so it can be restored. */
    @Column(name = "removed_by")
    private UUID removedBy;

    @Column(name = "removed_reason", columnDefinition = "text")
    private String removedReason;

    @Column(name = "removed_at")
    private Instant removedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
