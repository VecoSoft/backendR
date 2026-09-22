package com.bdreview.platform.community;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Same toggle/swap shape as CommunityPostVote, one row per (comment, user). */
@Entity
@Table(name = "community_post_comment_vote", uniqueConstraints =
        @UniqueConstraint(columnNames = {"comment_id", "user_id"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CommunityCommentVote {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "comment_id", nullable = false)
    private UUID commentId;

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
