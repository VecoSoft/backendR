package com.bdreview.platform.community;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * At most ONE reaction per (post, user) — unlike business.BusinessReaction
 * (which lets a user hold Like AND Love AND Wow simultaneously), a Facebook
 * post reaction is a single choice: reacting again with a different type
 * swaps it, reacting again with the same type removes it. See
 * CommunityPostService#react.
 */
@Entity
@Table(name = "community_post_reaction", uniqueConstraints =
        @UniqueConstraint(columnNames = {"post_id", "user_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CommunityPostReaction {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction_type", nullable = false, length = 10)
    private CommunityPostReactionType reactionType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
