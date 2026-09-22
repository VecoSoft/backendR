package com.bdreview.platform.community;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * At most ONE vote per (post, user) — Reddit-style: voting again with the
 * same type removes it, voting the other way swaps it. Formerly
 * CommunityPostReaction (6-value Facebook reactions); renamed+remapped to a
 * 2-value upvote/downvote in V33. See CommunityPostService#vote.
 */
@Entity
@Table(name = "community_post_vote", uniqueConstraints =
        @UniqueConstraint(columnNames = {"post_id", "user_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CommunityPostVote {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "vote_type", nullable = false, length = 10)
    private CommunityPostVoteType voteType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
